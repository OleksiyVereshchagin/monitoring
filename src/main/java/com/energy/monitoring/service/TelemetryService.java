package com.energy.monitoring.service;

import com.energy.monitoring.dto.DeviceRequest;
import com.energy.monitoring.dto.DeviceResponse;
import com.energy.monitoring.dto.HouseholdRequest;
import com.energy.monitoring.dto.HouseholdResponse;
import com.energy.monitoring.dto.ReadingRequest;
import com.energy.monitoring.dto.ReadingResponse;
import com.energy.monitoring.entity.BehaviorProfile;
import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.DeviceType;
import com.energy.monitoring.entity.Household;
import com.energy.monitoring.entity.Reading;
import com.energy.monitoring.entity.User;
import com.energy.monitoring.exception.ResourceNotFoundException;
import com.energy.monitoring.repository.DeviceRepository;
import com.energy.monitoring.repository.HouseholdRepository;
import com.energy.monitoring.repository.ReadingRepository;
import com.energy.monitoring.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TelemetryService {

    private final DeviceRepository deviceRepository;
    private final HouseholdRepository householdRepository;
    private final ReadingRepository readingRepository;
    private final UserRepository userRepository;
    private final DataGeneratorService dataGeneratorService;

    @Transactional
    public HouseholdResponse createHousehold(HouseholdRequest request, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Household household = Household.builder()
                .user(user)
                .name(request.name())
                .type(request.type())
                .build();

        return toHouseholdResponse(householdRepository.save(household));
    }

    @Transactional(readOnly = true)
    public List<HouseholdResponse> getHouseholds(Authentication authentication) {
        User user = getCurrentUser(authentication);
        return householdRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toHouseholdResponse)
                .toList();
    }

    @Transactional
    public HouseholdResponse updateHousehold(Long id, HouseholdRequest request, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Household household = getUserHousehold(id, user.getId());

        household.setName(request.name());
        household.setType(request.type());

        return toHouseholdResponse(householdRepository.save(household));
    }

    @Transactional
    public void deleteHousehold(Long id, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Household household = getUserHousehold(id, user.getId());
        householdRepository.delete(household);
    }

    @Transactional
    public DeviceResponse createDevice(DeviceRequest request, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Household household = resolveHousehold(request.householdId(), user.getId());
        Device device = Device.builder()
                .user(user)
                .household(household)
                .name(request.name())
                .type(request.type())
                .behaviorProfile(resolveBehaviorProfile(request.type(), request.behaviorProfile()))
                .nominalPower(request.nominalPower())
                .active(request.active() != null ? request.active() : true)
                .build();

        DeviceResponse response = toDeviceResponse(deviceRepository.save(device));

        dataGeneratorService.seedForUser(user.getId());

        return response;
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> getDevices(Long householdId, Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<Device> devices;

        if (householdId != null) {
            getUserHousehold(householdId, user.getId());
            devices = deviceRepository.findAllByUserIdAndHouseholdIdOrderByCreatedAtDesc(user.getId(), householdId);
        } else {
            devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId());
        }

        return devices.stream()
                .map(this::toDeviceResponse)
                .toList();
    }

    @Transactional
    public DeviceResponse updateDevice(Long id, DeviceRequest request, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Device device = getUserDevice(id, user.getId());
        Household household = resolveHousehold(request.householdId(), user.getId());

        device.setName(request.name());
        device.setHousehold(household);
        device.setType(request.type());
        device.setBehaviorProfile(resolveBehaviorProfile(request.type(), request.behaviorProfile()));
        device.setNominalPower(request.nominalPower());
        device.setActive(request.active() != null ? request.active() : true);

        return toDeviceResponse(deviceRepository.save(device));
    }

    @Transactional
    public void deleteDevice(Long id, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Device device = getUserDevice(id, user.getId());
        deviceRepository.delete(device);
    }

    @Transactional
    public ReadingResponse createReading(ReadingRequest request, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Device device = getUserDevice(request.deviceId(), user.getId());
        LocalDateTime timestamp = request.timestamp() != null ? request.timestamp() : LocalDateTime.now();

        Reading reading = Reading.builder()
                .device(device)
                .timestamp(timestamp)
                .powerConsumption(request.powerConsumption())
                .voltage(request.voltage())
                .current(request.current())
                .source(normalizeSource(request.source()))
                .sessionId(request.sessionId())
                .build();

        return toReadingResponse(readingRepository.save(reading));
    }

    @Transactional(readOnly = true)
    public Page<ReadingResponse> getReadings(
            Long deviceId,
            LocalDateTime from,
            LocalDateTime to,
            Pageable pageable,
            Authentication authentication
    ) {
        User user = getCurrentUser(authentication);

        if (deviceId != null) {
            getUserDevice(deviceId, user.getId());
        }

        return readingRepository.findAll(readingsFilter(user.getId(), deviceId, from, to), pageable)
                .map(this::toReadingResponse);
    }

    private Specification<Reading> readingsFilter(
            Long userId,
            Long deviceId,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return (root, query, criteriaBuilder) -> {
            var device = root.join("device");
            var user = device.join("user");
            var predicate = criteriaBuilder.equal(user.get("id"), userId);

            if (deviceId != null) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.equal(device.get("id"), deviceId));
            }
            if (from != null) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.greaterThanOrEqualTo(root.get("timestamp"), from));
            }
            if (to != null) {
                predicate = criteriaBuilder.and(predicate, criteriaBuilder.lessThanOrEqualTo(root.get("timestamp"), to));
            }

            return predicate;
        };
    }

    private User getCurrentUser(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user;
        }

        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
    }

    private Device getUserDevice(Long deviceId, Long userId) {
        return deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
    }

    private Household getUserHousehold(Long householdId, Long userId) {
        return householdRepository.findByIdAndUserId(householdId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
    }

    private Household resolveHousehold(Long requestedHouseholdId, Long userId) {
        if (requestedHouseholdId != null) {
            return getUserHousehold(requestedHouseholdId, userId);
        }

        List<Household> households = householdRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (households.isEmpty()) {
            throw new IllegalArgumentException("Create a household before adding devices");
        }
        if (households.size() > 1) {
            throw new IllegalArgumentException("householdId is required when user has multiple households");
        }

        return households.get(0);
    }

    private HouseholdResponse toHouseholdResponse(Household household) {
        return new HouseholdResponse(
                household.getId(),
                household.getName(),
                household.getType(),
                household.getCreatedAt()
        );
    }

    private DeviceResponse toDeviceResponse(Device device) {
        Household household = device.getHousehold();
        return new DeviceResponse(
                device.getId(),
                household != null ? household.getId() : null,
                household != null ? household.getName() : null,
                device.getName(),
                device.getType(),
                device.getBehaviorProfile(),
                device.getNominalPower(),
                device.getActive() != null ? device.getActive() : true,
                device.getCreatedAt()
        );
    }

    private ReadingResponse toReadingResponse(Reading reading) {
        Device device = reading.getDevice();
        return new ReadingResponse(
                reading.getId(),
                device.getId(),
                device.getName(),
                reading.getTimestamp(),
                reading.getPowerConsumption(),
                reading.getVoltage(),
                reading.getCurrent(),
                reading.getSource() != null ? reading.getSource() : "API",
                reading.getSessionId()
        );
    }

    private BehaviorProfile resolveBehaviorProfile(DeviceType type, BehaviorProfile requestedProfile) {
        if (requestedProfile != null) {
            return requestedProfile;
        }

        return switch (type) {
            case FRIDGE, REFRIGERATOR, FREEZER, AC, FAN, AIR_PURIFIER, GAS_BOILER -> BehaviorProfile.CYCLIC;
            case LIGHT, LIGHTING, TV, COMPUTER, DESKTOP_PC, LAPTOP, MONITOR,
                 GAME_CONSOLE, SPEAKERS, ROUTER, CHARGER, SMART_PLUG -> BehaviorProfile.INTERMITTENT;
            case OVEN, STOVE, MICROWAVE, KETTLE, COFFEE_MACHINE, WASHING_MACHINE,
                 TOASTER, IRON, HAIR_DRYER, DRYER, DISHWASHER, EV_CHARGER -> BehaviorProfile.PEAK_BASED;
            case HEATER, BOILER, WATER_PUMP, SOLAR_INVERTER, BATTERY_STORAGE, VACUUM, OTHER -> BehaviorProfile.CONSTANT;
        };
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "API";
        }

        return source.trim().toUpperCase(Locale.ROOT);
    }
}

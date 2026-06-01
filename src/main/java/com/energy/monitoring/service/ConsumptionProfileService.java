package com.energy.monitoring.service;

import com.energy.monitoring.dto.ConsumptionProfilePoint;
import com.energy.monitoring.entity.BehaviorProfile;
import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.DeviceType;
import com.energy.monitoring.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsumptionProfileService {

    private final DeviceRepository deviceRepository;

    public List<ConsumptionProfilePoint> hourlyProfile(Long userId) {
        LocalDateTime start = LocalDateTime.now()
                .truncatedTo(ChronoUnit.DAYS);
        return profile(userId, start, 48, 30);
    }

    public List<ConsumptionProfilePoint> hourlyForecast(Long userId) {
        LocalDateTime start = LocalDateTime.now()
                .truncatedTo(ChronoUnit.HOURS)
                .plusHours(1);
        return profile(userId, start, 24, 60);
    }

    public List<Double> tenMinuteForecast(Long userId, int points) {
        List<Device> devices = activeDevices(userId);
        LocalDateTime start = alignToTenMinuteStep(LocalDateTime.now()).plusMinutes(10);
        List<Double> result = new ArrayList<>();

        for (int i = 0; i < points; i++) {
            LocalDateTime timestamp = start.plusMinutes((long) i * 10);
            result.add(roundPower(totalPower(devices, timestamp)));
        }

        return result;
    }

    public double currentPower(Long userId) {
        return roundPower(totalPower(activeDevices(userId), LocalDateTime.now()));
    }

    private List<ConsumptionProfilePoint> profile(Long userId, LocalDateTime start, int points, int stepMinutes) {
        List<Device> devices = activeDevices(userId);
        List<ConsumptionProfilePoint> result = new ArrayList<>();

        for (int i = 0; i < points; i++) {
            LocalDateTime timestamp = start.plusMinutes((long) i * stepMinutes);
            result.add(new ConsumptionProfilePoint(timestamp, roundPower(totalPower(devices, timestamp))));
        }

        return result;
    }

    private List<Device> activeDevices(Long userId) {
        return deviceRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
    }

    private double totalPower(List<Device> devices, LocalDateTime timestamp) {
        if (devices.isEmpty()) {
            return 0.0;
        }

        return devices.stream()
                .mapToDouble(device -> devicePower(device, timestamp))
                .sum();
    }

    private double devicePower(Device device, LocalDateTime timestamp) {
        double nominal = nominalPower(device);
        double hour = timestamp.getHour() + timestamp.getMinute() / 60.0;
        double typeFactor = typeFactor(device.getType(), hour);
        double profileFactor = behaviorFactor(device.getBehaviorProfile(), hour);
        return nominal * typeFactor * profileFactor;
    }

    private double nominalPower(Device device) {
        BigDecimal nominal = device.getNominalPower();
        if (nominal != null && nominal.compareTo(BigDecimal.ZERO) > 0) {
            return nominal.doubleValue();
        }

        return fallbackNominal(device.getType());
    }

    private double typeFactor(DeviceType type, double hour) {
        double morning = peak(hour, 8.0, 1.45);
        double evening = peak(hour, 20.0, 1.85);
        double midday = peak(hour, 13.0, 3.2);
        double night = peak(hour, 2.5, 2.8);

        return switch (type) {
            case FRIDGE, REFRIGERATOR, FREEZER -> 0.42 + 0.08 * midday + 0.03 * night;
            case ROUTER, SMART_PLUG, CHARGER -> 1.0;
            case LIGHT, LIGHTING -> 0.07 + 0.22 * morning + 0.9 * evening;
            case TV, COMPUTER, LAPTOP -> 0.06 + 0.12 * midday + 0.72 * evening;
            case BOILER -> 0.1 + 0.82 * morning + 0.6 * evening;
            case HEATER, AC -> 0.12 + 0.46 * morning + 0.52 * evening + 0.12 * night;
            case WASHING_MACHINE, DRYER, DISHWASHER -> 0.03 + 0.35 * morning + 0.62 * evening;
            case OVEN, STOVE, MICROWAVE, KETTLE, COFFEE_MACHINE -> 0.025 + 0.28 * morning + 0.7 * evening;
            case VACUUM -> 0.015 + 0.18 * midday + 0.08 * evening;
            case WATER_PUMP -> 0.16 + 0.24 * morning + 0.28 * evening;
            case EV_CHARGER -> 0.03 + 0.5 * night;
            case SOLAR_INVERTER, BATTERY_STORAGE -> 0.08 + 0.22 * midday;
            case OTHER -> 0.1 + 0.24 * morning + 0.38 * evening;
        };
    }

    private double behaviorFactor(BehaviorProfile profile, double hour) {
        if (profile == null) {
            return 1.0;
        }

        double morning = peak(hour, 8.0, 1.6);
        double evening = peak(hour, 20.0, 1.9);
        double activePartOfDay = Math.max(morning, evening);

        return switch (profile) {
            case CONSTANT -> 0.95;
            case CYCLIC -> 0.78 + 0.16 * Math.sin((hour - 6.0) / 24.0 * 2.0 * Math.PI);
            case INTERMITTENT -> 0.32 + 0.68 * activePartOfDay;
            case PEAK_BASED -> 0.12 + 0.88 * activePartOfDay;
        };
    }

    private double fallbackNominal(DeviceType type) {
        return switch (type) {
            case FRIDGE, REFRIGERATOR, FREEZER -> 0.18;
            case TV -> 0.12;
            case AC -> 1.4;
            case HEATER -> 1.7;
            case BOILER -> 1.8;
            case WASHING_MACHINE, DRYER, DISHWASHER -> 1.4;
            case OVEN, STOVE -> 2.0;
            case MICROWAVE, KETTLE, COFFEE_MACHINE -> 1.2;
            case LIGHT, LIGHTING -> 0.08;
            case COMPUTER, LAPTOP -> 0.18;
            case ROUTER, CHARGER, SMART_PLUG -> 0.03;
            case VACUUM -> 1.0;
            case WATER_PUMP -> 0.5;
            case EV_CHARGER -> 3.5;
            case SOLAR_INVERTER, BATTERY_STORAGE -> 0.4;
            case OTHER -> 0.4;
        };
    }

    private double peak(double hour, double center, double width) {
        double direct = Math.abs(hour - center);
        double circularDistance = Math.min(direct, 24.0 - direct);
        return Math.exp(-Math.pow(circularDistance, 2.0) / (2.0 * Math.pow(width, 2.0)));
    }

    private LocalDateTime alignToTenMinuteStep(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MINUTES)
                .withMinute((time.getMinute() / 10) * 10)
                .withSecond(0)
                .withNano(0);
    }

    private double roundPower(double value) {
        return Math.round(Math.max(0.0, value) * 100.0) / 100.0;
    }
}

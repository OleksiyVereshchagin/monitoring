package com.energy.monitoring.service;

import com.energy.monitoring.dto.SimulationProfileRequest;
import com.energy.monitoring.dto.SimulationProfileResponse;
import com.energy.monitoring.entity.ActivityLevel;
import com.energy.monitoring.entity.Household;
import com.energy.monitoring.entity.HouseholdType;
import com.energy.monitoring.entity.PresenceMode;
import com.energy.monitoring.entity.SimulationProfile;
import com.energy.monitoring.entity.User;
import com.energy.monitoring.exception.ResourceNotFoundException;
import com.energy.monitoring.repository.HouseholdRepository;
import com.energy.monitoring.repository.SimulationProfileRepository;
import com.energy.monitoring.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class SimulationProfileService {

    private static final int DEFAULT_OCCUPANTS = 2;
    private static final BigDecimal DEFAULT_AREA_M2 = BigDecimal.valueOf(55);
    private static final String DEFAULT_CITY = "Kyiv";
    private static final String DEFAULT_WEEKEND_DAYS = "SATURDAY,SUNDAY";
    private static final LocalTime DEFAULT_SLEEP_START = LocalTime.of(23, 30);
    private static final LocalTime DEFAULT_SLEEP_END = LocalTime.of(7, 0);
    private static final LocalTime DEFAULT_AWAY_START = LocalTime.of(9, 0);
    private static final LocalTime DEFAULT_AWAY_END = LocalTime.of(17, 30);
    private static final String OFFICE_ACTIVE_DAYS = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY";
    private static final LocalTime OFFICE_WORK_START = LocalTime.of(9, 0);
    private static final LocalTime OFFICE_WORK_END = LocalTime.of(18, 0);
    private static final String COTTAGE_VISIT_DAYS = "SATURDAY,SUNDAY";
    private static final LocalTime COTTAGE_VISIT_START = LocalTime.of(10, 0);
    private static final LocalTime COTTAGE_VISIT_END = LocalTime.of(20, 0);
    private static final LocalTime DEFAULT_CUSTOM_HOME_START_1 = LocalTime.of(7, 0);
    private static final LocalTime DEFAULT_CUSTOM_HOME_END_1 = LocalTime.of(9, 0);
    private final SimulationProfileRepository simulationProfileRepository;
    private final HouseholdRepository householdRepository;
    private final UserRepository userRepository;
    private final DataGeneratorService dataGeneratorService;

    @Transactional
    public SimulationProfileResponse getOrCreate(Long householdId, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Household household = resolveHousehold(user, householdId);
        return toResponse(getOrCreateForHousehold(user, household));
    }

    @Transactional
    public SimulationProfileResponse update(SimulationProfileRequest request, Long householdId, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Household household = resolveHousehold(user, householdId);
        SimulationProfile profile = getOrCreateForHousehold(user, household);
        applyRequest(profile, request);
        return toResponse(simulationProfileRepository.save(profile));
    }

    @Transactional
    public SimulationProfileResponse applyDefaults(Long householdId, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Household household = resolveHousehold(user, householdId);
        SimulationProfile profile = getOrCreateForHousehold(user, household);
        applyDefaults(profile);
        return toResponse(simulationProfileRepository.save(profile));
    }

    @Transactional
    public int regenerateRecent(Long householdId, Authentication authentication) {
        User user = getCurrentUser(authentication);
        Household household = resolveHousehold(user, householdId);
        getOrCreateForHousehold(user, household);
        return dataGeneratorService.regenerateRecentDataForUserAndHousehold(user.getId(), household.getId());
    }

    private SimulationProfile getOrCreateForHousehold(User user, Household household) {
        return simulationProfileRepository
                .findFirstByUserIdAndHouseholdIdOrderByIdDesc(user.getId(), household.getId())
                .orElseGet(() -> simulationProfileRepository.save(defaultProfile(user, household)));
    }

    private SimulationProfile defaultProfile(User user, Household household) {
        SimulationProfile profile = SimulationProfile.builder()
                .user(user)
                .household(household)
                .build();
        applyDefaults(profile);
        return profile;
    }

    private Household resolveHousehold(User user, Long householdId) {
        if (householdId != null) {
            return householdRepository.findByIdAndUserId(householdId, user.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Household not found"));
        }

        return householdRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Create a household before configuring simulation"));
    }

    private void applyDefaults(SimulationProfile profile) {
        HouseholdType type = profile.getHousehold() != null ? profile.getHousehold().getType() : null;
        boolean office = type == HouseholdType.OFFICE;
        boolean cottage = type == HouseholdType.COTTAGE;

        profile.setOccupants(office ? 6 : DEFAULT_OCCUPANTS);
        profile.setAreaM2(office ? BigDecimal.valueOf(85) : cottage ? BigDecimal.valueOf(45) : DEFAULT_AREA_M2);
        profile.setCity(DEFAULT_CITY);
        profile.setActivityLevel(cottage ? ActivityLevel.ECONOMY : ActivityLevel.NORMAL);
        profile.setPresenceMode(cottage ? PresenceMode.PARTLY_HOME : PresenceMode.STANDARD_WORKDAY);
        profile.setSleepStart(DEFAULT_SLEEP_START);
        profile.setSleepEnd(DEFAULT_SLEEP_END);
        profile.setAwayStart(office ? OFFICE_WORK_START : cottage ? COTTAGE_VISIT_START : DEFAULT_AWAY_START);
        profile.setAwayEnd(office ? OFFICE_WORK_END : cottage ? COTTAGE_VISIT_END : DEFAULT_AWAY_END);
        profile.setWeekendDays(office ? OFFICE_ACTIVE_DAYS : cottage ? COTTAGE_VISIT_DAYS : DEFAULT_WEEKEND_DAYS);
        profile.setCustomHomeStart1(office ? OFFICE_WORK_START : cottage ? COTTAGE_VISIT_START : DEFAULT_CUSTOM_HOME_START_1);
        profile.setCustomHomeEnd1(office ? OFFICE_WORK_END : cottage ? COTTAGE_VISIT_END : DEFAULT_CUSTOM_HOME_END_1);
        profile.setCustomHomeActivity1(ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed1(false);
        profile.setCustomHomeStart2(null);
        profile.setCustomHomeEnd2(null);
        profile.setCustomHomeActivity2(ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed2(false);
        profile.setCustomHomeStart3(null);
        profile.setCustomHomeEnd3(null);
        profile.setCustomHomeActivity3(ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed3(false);
        profile.setCustomHomeStart4(null);
        profile.setCustomHomeEnd4(null);
        profile.setCustomHomeActivity4(ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed4(false);
        profile.setCustomHomeStart5(null);
        profile.setCustomHomeEnd5(null);
        profile.setCustomHomeActivity5(ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed5(false);
    }

    private void applyRequest(SimulationProfile profile, SimulationProfileRequest request) {
        profile.setOccupants(request.occupants() != null ? request.occupants() : DEFAULT_OCCUPANTS);
        profile.setAreaM2(request.areaM2() != null ? request.areaM2() : DEFAULT_AREA_M2);
        profile.setCity(hasText(request.city()) ? request.city().trim() : DEFAULT_CITY);
        profile.setActivityLevel(request.activityLevel() != null ? request.activityLevel() : ActivityLevel.NORMAL);
        profile.setPresenceMode(request.presenceMode() != null ? request.presenceMode() : PresenceMode.STANDARD_WORKDAY);
        profile.setSleepStart(request.sleepStart() != null ? request.sleepStart() : DEFAULT_SLEEP_START);
        profile.setSleepEnd(request.sleepEnd() != null ? request.sleepEnd() : DEFAULT_SLEEP_END);
        profile.setAwayStart(request.awayStart() != null ? request.awayStart() : DEFAULT_AWAY_START);
        profile.setAwayEnd(request.awayEnd() != null ? request.awayEnd() : DEFAULT_AWAY_END);
        profile.setWeekendDays(hasText(request.weekendDays()) ? request.weekendDays().trim() : DEFAULT_WEEKEND_DAYS);
        profile.setCustomHomeStart1(request.customHomeStart1() != null ? request.customHomeStart1() : DEFAULT_CUSTOM_HOME_START_1);
        profile.setCustomHomeEnd1(request.customHomeEnd1() != null ? request.customHomeEnd1() : DEFAULT_CUSTOM_HOME_END_1);
        profile.setCustomHomeActivity1(request.customHomeActivity1() != null ? request.customHomeActivity1() : ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed1(Boolean.TRUE.equals(request.customHomeHeavyAllowed1()));
        profile.setCustomHomeStart2(request.customHomeStart2());
        profile.setCustomHomeEnd2(request.customHomeEnd2());
        profile.setCustomHomeActivity2(request.customHomeActivity2() != null ? request.customHomeActivity2() : ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed2(Boolean.TRUE.equals(request.customHomeHeavyAllowed2()));
        profile.setCustomHomeStart3(request.customHomeStart3());
        profile.setCustomHomeEnd3(request.customHomeEnd3());
        profile.setCustomHomeActivity3(request.customHomeActivity3() != null ? request.customHomeActivity3() : ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed3(Boolean.TRUE.equals(request.customHomeHeavyAllowed3()));
        profile.setCustomHomeStart4(request.customHomeStart4());
        profile.setCustomHomeEnd4(request.customHomeEnd4());
        profile.setCustomHomeActivity4(request.customHomeActivity4() != null ? request.customHomeActivity4() : ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed4(Boolean.TRUE.equals(request.customHomeHeavyAllowed4()));
        profile.setCustomHomeStart5(request.customHomeStart5());
        profile.setCustomHomeEnd5(request.customHomeEnd5());
        profile.setCustomHomeActivity5(request.customHomeActivity5() != null ? request.customHomeActivity5() : ActivityLevel.NORMAL);
        profile.setCustomHomeHeavyAllowed5(Boolean.TRUE.equals(request.customHomeHeavyAllowed5()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private User getCurrentUser(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user;
        }

        return userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("Authenticated user not found"));
    }

    private SimulationProfileResponse toResponse(SimulationProfile profile) {
        return new SimulationProfileResponse(
                profile.getId(),
                profile.getHousehold() != null ? profile.getHousehold().getId() : null,
                profile.getHousehold() != null ? profile.getHousehold().getName() : null,
                profile.getOccupants(),
                profile.getAreaM2(),
                profile.getCity(),
                profile.getActivityLevel(),
                profile.getPresenceMode(),
                profile.getSleepStart(),
                profile.getSleepEnd(),
                profile.getAwayStart(),
                profile.getAwayEnd(),
                profile.getWeekendDays(),
                profile.getCustomHomeStart1(),
                profile.getCustomHomeEnd1(),
                profile.getCustomHomeActivity1(),
                profile.getCustomHomeHeavyAllowed1(),
                profile.getCustomHomeStart2(),
                profile.getCustomHomeEnd2(),
                profile.getCustomHomeActivity2(),
                profile.getCustomHomeHeavyAllowed2(),
                profile.getCustomHomeStart3(),
                profile.getCustomHomeEnd3(),
                profile.getCustomHomeActivity3(),
                profile.getCustomHomeHeavyAllowed3(),
                profile.getCustomHomeStart4(),
                profile.getCustomHomeEnd4(),
                profile.getCustomHomeActivity4(),
                profile.getCustomHomeHeavyAllowed4(),
                profile.getCustomHomeStart5(),
                profile.getCustomHomeEnd5(),
                profile.getCustomHomeActivity5(),
                profile.getCustomHomeHeavyAllowed5(),
                profile.getUpdatedAt()
        );
    }
}

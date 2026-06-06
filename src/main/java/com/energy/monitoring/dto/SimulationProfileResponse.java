package com.energy.monitoring.dto;

import com.energy.monitoring.entity.ActivityLevel;
import com.energy.monitoring.entity.PresenceMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

public record SimulationProfileResponse(
        Long id,
        Long householdId,
        String householdName,
        Integer occupants,
        BigDecimal areaM2,
        String city,
        ActivityLevel activityLevel,
        PresenceMode presenceMode,
        LocalTime sleepStart,
        LocalTime sleepEnd,
        LocalTime awayStart,
        LocalTime awayEnd,
        String weekendDays,
        LocalTime customHomeStart1,
        LocalTime customHomeEnd1,
        ActivityLevel customHomeActivity1,
        Boolean customHomeHeavyAllowed1,
        LocalTime customHomeStart2,
        LocalTime customHomeEnd2,
        ActivityLevel customHomeActivity2,
        Boolean customHomeHeavyAllowed2,
        LocalTime customHomeStart3,
        LocalTime customHomeEnd3,
        ActivityLevel customHomeActivity3,
        Boolean customHomeHeavyAllowed3,
        LocalTime customHomeStart4,
        LocalTime customHomeEnd4,
        ActivityLevel customHomeActivity4,
        Boolean customHomeHeavyAllowed4,
        LocalTime customHomeStart5,
        LocalTime customHomeEnd5,
        ActivityLevel customHomeActivity5,
        Boolean customHomeHeavyAllowed5,
        LocalDateTime updatedAt
) {
}

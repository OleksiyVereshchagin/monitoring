package com.energy.monitoring.dto;

import com.energy.monitoring.entity.BehaviorProfile;
import com.energy.monitoring.entity.DeviceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DeviceResponse(
        Long id,
        Long householdId,
        String householdName,
        String name,
        DeviceType type,
        BehaviorProfile behaviorProfile,
        BigDecimal nominalPower,
        Boolean active,
        LocalDateTime createdAt
) {}

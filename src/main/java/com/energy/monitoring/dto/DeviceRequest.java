package com.energy.monitoring.dto;

import com.energy.monitoring.entity.BehaviorProfile;
import com.energy.monitoring.entity.DeviceType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DeviceRequest(
        @NotBlank String name,
        Long householdId,
        @NotNull DeviceType type,
        BehaviorProfile behaviorProfile,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal nominalPower,
        Boolean active
) {}

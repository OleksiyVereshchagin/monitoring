package com.energy.monitoring.dto;

import com.energy.monitoring.entity.HouseholdType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record HouseholdRequest(
        @NotBlank String name,
        @NotNull HouseholdType type
) {}

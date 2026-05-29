package com.energy.monitoring.dto;

import com.energy.monitoring.entity.HouseholdType;

import java.time.LocalDateTime;

public record HouseholdResponse(
        Long id,
        String name,
        HouseholdType type,
        LocalDateTime createdAt
) {}

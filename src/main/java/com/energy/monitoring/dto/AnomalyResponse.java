package com.energy.monitoring.dto;

import com.energy.monitoring.entity.AnomalyType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AnomalyResponse(
        Long id,
        Long deviceId,
        String deviceName,
        LocalDateTime timestamp,
        BigDecimal actualValue,
        BigDecimal expectedValue,
        BigDecimal deviationPercent,
        AnomalyType type,
        LocalDateTime detectedAt
) {}
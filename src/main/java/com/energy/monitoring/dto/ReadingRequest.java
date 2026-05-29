package com.energy.monitoring.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReadingRequest(
        @NotNull Long deviceId,
        LocalDateTime timestamp,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal powerConsumption,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal voltage,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal current,
        String source,
        String sessionId
) {}

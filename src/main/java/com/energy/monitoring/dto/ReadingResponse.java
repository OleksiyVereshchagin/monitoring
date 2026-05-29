package com.energy.monitoring.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReadingResponse(
        Long id,
        Long deviceId,
        String deviceName,
        LocalDateTime timestamp,
        BigDecimal powerConsumption,
        BigDecimal voltage,
        BigDecimal current,
        String source,
        String sessionId
) {}

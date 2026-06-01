package com.energy.monitoring.dto;

import java.time.LocalDateTime;

public record ConsumptionProfilePoint(
        LocalDateTime timestamp,
        double powerKw
) {
}

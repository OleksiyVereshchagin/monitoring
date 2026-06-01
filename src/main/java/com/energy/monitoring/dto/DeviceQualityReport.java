package com.energy.monitoring.dto;

import java.time.LocalDateTime;

public record DeviceQualityReport(
        Long deviceId,
        String deviceName,
        int readingCount,
        int duplicateCount,
        int misalignedTimestampCount,
        int missingTimestampCount,
        int availableTrainingWindows,
        LocalDateTime firstTimestamp,
        LocalDateTime lastTimestamp
) {
}

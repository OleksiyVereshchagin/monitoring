package com.energy.monitoring.dto;

import java.util.List;

public record DataQualityReport(
        int stepMinutes,
        int windowSize,
        int forecastSize,
        int totalDevices,
        int activeDevices,
        int totalReadings,
        int duplicateCount,
        int misalignedTimestampCount,
        int missingTimestampCount,
        int availableTrainingWindows,
        boolean trainingReady,
        List<String> issues,
        List<DeviceQualityReport> devices
) {
}

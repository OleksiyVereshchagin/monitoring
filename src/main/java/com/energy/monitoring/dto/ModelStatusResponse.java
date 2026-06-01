package com.energy.monitoring.dto;

import java.time.LocalDateTime;

public record ModelStatusResponse(
        String status,
        boolean modelReady,
        String message,
        Long userId,
        LocalDateTime trainedAt,
        Long modelSizeBytes,
        Integer totalPoints,
        Integer totalSamples,
        Integer trainSamples,
        Integer validationSamples,
        Integer testSamples,
        Double trainMse,
        Double trainMae,
        Double validationMse,
        Double validationMae,
        Double testMse,
        Double testMae,
        DataQualityReport dataQuality
) {
}

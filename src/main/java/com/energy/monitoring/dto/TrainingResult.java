package com.energy.monitoring.dto;

public record TrainingResult(
        String status,
        int totalPoints,
        int totalSamples,
        int trainSamples,
        int validationSamples,
        int testSamples,
        double trainMse,
        double trainMae,
        double validationMse,
        double validationMae,
        double testMse,
        double testMae,
        double minValue,
        double maxValue,
        DataQualityReport dataQuality
) {
}

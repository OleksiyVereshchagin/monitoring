package com.energy.monitoring.dto;

public record DeviceContributionResponse(
        Long deviceId,
        String deviceName,
        String deviceType,
        double totalKwh,
        double percentage
) {
}

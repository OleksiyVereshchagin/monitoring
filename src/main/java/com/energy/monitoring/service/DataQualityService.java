package com.energy.monitoring.service;

import com.energy.monitoring.dto.DataQualityReport;
import com.energy.monitoring.dto.DeviceQualityReport;
import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.Reading;
import com.energy.monitoring.repository.DeviceRepository;
import com.energy.monitoring.repository.ReadingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataQualityService {

    public static final int STEP_MINUTES = 10;

    private final DeviceRepository deviceRepository;
    private final ReadingRepository readingRepository;

    public DataQualityReport analyzeUserSeries(Long userId, int windowSize, int forecastSize) {
        List<Device> allDevices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        List<Device> activeDevices = deviceRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);

        List<DeviceQualityReport> deviceReports = new ArrayList<>();
        int totalReadings = 0;
        int totalDuplicates = 0;
        int totalMisaligned = 0;
        int totalMissing = 0;
        int totalWindows = 0;

        for (Device device : activeDevices) {
            List<Reading> readings = readingRepository.findByDeviceAndSourceNotOrderByTimestampAsc(device, "SIMULATED_ANOMALY");
            DeviceQualityReport report = analyzeDevice(device, readings, windowSize, forecastSize);
            deviceReports.add(report);
            totalReadings += report.readingCount();
            totalDuplicates += report.duplicateCount();
            totalMisaligned += report.misalignedTimestampCount();
            totalMissing += report.missingTimestampCount();
            totalWindows += report.availableTrainingWindows();
        }

        List<String> issues = new ArrayList<>();
        if (activeDevices.isEmpty()) {
            issues.add("No active devices found for the user.");
        }
        if (totalReadings == 0) {
            issues.add("No readings found for active devices.");
        }
        if (totalMisaligned > 0) {
            issues.add("Misaligned timestamps detected. Readings must be exactly on the 10-minute grid with zero seconds.");
        }
        if (totalMissing > 0) {
            issues.add("Missing timestamps detected. Expected continuous 10-minute time series.");
        }
        if (totalWindows == 0) {
            issues.add("Not enough continuous data to build training windows.");
        }

        boolean trainingReady = issues.isEmpty();
        return new DataQualityReport(
                STEP_MINUTES,
                windowSize,
                forecastSize,
                allDevices.size(),
                activeDevices.size(),
                totalReadings,
                totalDuplicates,
                totalMisaligned,
                totalMissing,
                totalWindows,
                trainingReady,
                issues,
                deviceReports
        );
    }

    public DataQualityReport ensureTrainingReady(Long userId, int windowSize, int forecastSize) {
        DataQualityReport report = analyzeUserSeries(userId, windowSize, forecastSize);
        if (!report.trainingReady()) {
            throw new IllegalStateException("Dataset is not ready for ML training: " + String.join("; ", report.issues()));
        }
        return report;
    }

    private DeviceQualityReport analyzeDevice(Device device, List<Reading> readings, int windowSize, int forecastSize) {
        Map<LocalDateTime, Integer> timestampCounts = new LinkedHashMap<>();
        for (Reading reading : readings) {
            timestampCounts.merge(reading.getTimestamp(), 1, Integer::sum);
        }

        int duplicates = timestampCounts.values().stream()
                .filter(count -> count > 1)
                .mapToInt(count -> count - 1)
                .sum();
        int misaligned = (int) timestampCounts.keySet().stream()
                .filter(this::isMisaligned)
                .count();

        List<LocalDateTime> uniqueTimestamps = new ArrayList<>(timestampCounts.keySet());
        int missing = countMissingTimestamps(uniqueTimestamps);
        int windows = countAvailableWindows(uniqueTimestamps, windowSize, forecastSize);

        LocalDateTime first = uniqueTimestamps.isEmpty() ? null : uniqueTimestamps.get(0);
        LocalDateTime last = uniqueTimestamps.isEmpty() ? null : uniqueTimestamps.get(uniqueTimestamps.size() - 1);

        return new DeviceQualityReport(
                device.getId(),
                device.getName(),
                readings.size(),
                duplicates,
                misaligned,
                missing,
                windows,
                first,
                last
        );
    }

    private boolean isMisaligned(LocalDateTime timestamp) {
        return timestamp.getMinute() % STEP_MINUTES != 0
                || timestamp.getSecond() != 0
                || timestamp.getNano() != 0;
    }

    private int countMissingTimestamps(List<LocalDateTime> timestamps) {
        int missing = 0;
        for (int i = 1; i < timestamps.size(); i++) {
            long minutes = Duration.between(timestamps.get(i - 1), timestamps.get(i)).toMinutes();
            if (minutes > STEP_MINUTES && minutes % STEP_MINUTES == 0) {
                missing += (int) (minutes / STEP_MINUTES) - 1;
            } else if (minutes != STEP_MINUTES) {
                missing++;
            }
        }
        return missing;
    }

    private int countAvailableWindows(List<LocalDateTime> timestamps, int windowSize, int forecastSize) {
        if (timestamps.isEmpty()) {
            return 0;
        }

        int windows = 0;
        int segmentLength = 1;
        int requiredLength = windowSize + forecastSize;

        for (int i = 1; i < timestamps.size(); i++) {
            long minutes = Duration.between(timestamps.get(i - 1), timestamps.get(i)).toMinutes();
            if (minutes == STEP_MINUTES) {
                segmentLength++;
            } else {
                windows += Math.max(0, segmentLength - requiredLength + 1);
                segmentLength = 1;
            }
        }

        windows += Math.max(0, segmentLength - requiredLength + 1);
        return windows;
    }
}

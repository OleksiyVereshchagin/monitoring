package com.energy.monitoring.controller;

import com.energy.monitoring.dto.AnomalyResponse;
import com.energy.monitoring.dto.DataQualityReport;
import com.energy.monitoring.dto.ModelStatusResponse;
import com.energy.monitoring.dto.TrainingResult;
import com.energy.monitoring.entity.Anomaly;
import com.energy.monitoring.entity.Device;
import com.energy.monitoring.repository.AnomalyRepository;
import com.energy.monitoring.repository.DeviceRepository;
import com.energy.monitoring.repository.ReadingRepository;
import com.energy.monitoring.service.DataGeneratorService;
import com.energy.monitoring.service.DataQualityService;
import com.energy.monitoring.service.MLService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
public class MLController {

    private static final String SIMULATED_ANOMALY_SOURCE = "SIMULATED_ANOMALY";

    private final MLService mlService;
    private final DeviceRepository deviceRepository;
    private final ReadingRepository readingRepository;
    private final AnomalyRepository anomalyRepository;
    private final DataQualityService dataQualityService;
    private final DataGeneratorService dataGeneratorService;

    @PostMapping("/train")
    public ResponseEntity<TrainingResult> train(Authentication authentication) {
        Long userId = getUserId(authentication);
        TrainingResult result = mlService.train(userId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/use-demo-model")
    public ResponseEntity<ModelStatusResponse> useDemoModel(Authentication authentication) {
        Long userId = getUserId(authentication);
        dataGeneratorService.ensureRecentDataForUser(userId);
        return ResponseEntity.ok(mlService.useDemoModel(userId));
    }

    @GetMapping("/forecast")
    public ResponseEntity<List<Double>> forecast(Authentication authentication) {
        Long userId = getUserId(authentication);
        dataGeneratorService.ensureRecentDataForUser(userId);
        List<Double> result = mlService.forecast(userId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/model-status")
    public ResponseEntity<ModelStatusResponse> modelStatus(Authentication authentication) {
        Long userId = getUserId(authentication);
        dataGeneratorService.ensureRecentDataForUser(userId);
        return ResponseEntity.ok(mlService.getModelStatus(userId));
    }

    @GetMapping("/dataset-info")
    public ResponseEntity<Map<String, Object>> datasetInfo(Authentication authentication) {
        Long userId = getUserId(authentication);
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        List<Double> series = readingRepository
                .findAggregatedByDevicesOrderByTimestampExcludingSource(devices, "SIMULATED_ANOMALY")
                .stream()
                .map(Double::valueOf)
                .toList();

        int maxPoints = 14 * 144;
        int usedPoints = Math.min(series.size(), maxPoints);

        return ResponseEntity.ok(Map.of(
                "totalPoints", series.size(),
                "usedForTraining", usedPoints,
                "devices", devices.size(),
                "minValue", series.stream().mapToDouble(Double::doubleValue).min().orElse(0),
                "maxValue", series.stream().mapToDouble(Double::doubleValue).max().orElse(0)
        ));
    }

    @GetMapping("/dataset-quality")
    public ResponseEntity<DataQualityReport> datasetQuality(Authentication authentication) {
        Long userId = getUserId(authentication);
        return ResponseEntity.ok(dataQualityService.analyzeUserSeries(userId, 144, 144));
    }

    @PostMapping("/detect-anomalies")
    public ResponseEntity<List<AnomalyResponse>> detectAnomalies(Authentication authentication) {
        Long userId = getUserId(authentication);
        List<Anomaly> anomalies = mlService.detectAnomalies(userId);
        return ResponseEntity.ok(toAnomalyResponses(anomalies));
    }

    @GetMapping("/anomalies")
    public ResponseEntity<List<AnomalyResponse>> getAnomalies(
            @RequestParam(defaultValue = "0") int limit,
            Authentication authentication) {
        Long userId = getUserId(authentication);
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<Anomaly> anomalies = anomalyRepository.findAllByDeviceInOrderByTimestampDesc(devices);
        List<AnomalyResponse> response = toAnomalyResponses(anomalies);
        if (limit > 0) {
            response = response.stream().limit(limit).toList();
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/simulate-anomaly")
    public ResponseEntity<Map<String, String>> simulateAnomaly(
            @RequestParam(required = false) Long deviceId,
            Authentication authentication) {
        Long userId = getUserId(authentication);
        Anomaly anomaly = mlService.simulateAnomaly(deviceId, userId);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "Anomaly inserted.",
                "anomalyId", anomaly.getId().toString()
        ));
    }

    @GetMapping("/readings/hourly")
    public ResponseEntity<Map<String, Object>> hourlyReadings(Authentication authentication) {
        Long userId = getUserId(authentication);
        dataGeneratorService.ensureRecentDataForUser(userId);

        LocalDateTime from = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS);
        List<Object[]> rows = readingRepository.findHourlyAggregated(userId, from, SIMULATED_ANOMALY_SOURCE);
        List<String> labels = new ArrayList<>();
        List<Double> values = new ArrayList<>();

        for (Object[] row : rows) {
            labels.add(toLocalDateTime(row[0]).toString());
            values.add(row[1] != null ? ((Number) row[1]).doubleValue() : null);
        }

        return ResponseEntity.ok(Map.of("labels", labels, "values", values));
    }

    @GetMapping("/current-power")
    public ResponseEntity<Map<String, Object>> currentPower(Authentication authentication) {
        Long userId = getUserId(authentication);
        dataGeneratorService.ensureRecentDataForUser(userId);
        Double total = readingRepository.findLatestTotalConsumption(userId, SIMULATED_ANOMALY_SOURCE);
        return ResponseEntity.ok(Map.of(
                "value", total != null ? String.format("%.2f", total) : "0.00"
        ));
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().replace(" ", "T"));
    }

    private List<AnomalyResponse> toAnomalyResponses(List<Anomaly> anomalies) {
        return anomalies.stream()
                .map(a -> new AnomalyResponse(
                        a.getId(),
                        a.getDevice().getId(),
                        a.getDevice().getName(),
                        a.getTimestamp(),
                        a.getActualValue(),
                        a.getExpectedValue(),
                        a.getDeviationPercent(),
                        a.getType(),
                        a.getDetectedAt()
                ))
                .toList();
    }

    private Long getUserId(Authentication authentication) {
        com.energy.monitoring.entity.User user =
                (com.energy.monitoring.entity.User) authentication.getPrincipal();
        return user.getId();
    }
}

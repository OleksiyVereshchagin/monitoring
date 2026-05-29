package com.energy.monitoring.controller;

import com.energy.monitoring.dto.AnomalyResponse;
import com.energy.monitoring.entity.Anomaly;
import com.energy.monitoring.entity.Device;
import com.energy.monitoring.repository.AnomalyRepository;
import com.energy.monitoring.repository.DeviceRepository;
import com.energy.monitoring.repository.ReadingRepository;
import com.energy.monitoring.service.MLService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ml")
@RequiredArgsConstructor
public class MLController {

    private final MLService mlService;
    private final DeviceRepository deviceRepository;
    private final ReadingRepository readingRepository;
    private final AnomalyRepository anomalyRepository;

    @PostMapping("/train")
    public ResponseEntity<Map<String, String>> train(Authentication authentication) {
        Long userId = getUserId(authentication);
        mlService.train(userId);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Навчання завершено успішно."));
    }

    @GetMapping("/forecast")
    public ResponseEntity<List<Double>> forecast(Authentication authentication) {
        Long userId = getUserId(authentication);
        List<Double> result = mlService.forecast(userId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/dataset-info")
    public ResponseEntity<Map<String, Object>> datasetInfo(Authentication authentication) {
        Long userId = getUserId(authentication);
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        List<Double> series = readingRepository
                .findAggregatedByDevicesOrderByTimestamp(devices)
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

    @PostMapping("/detect-anomalies")
    public ResponseEntity<List<AnomalyResponse>> detectAnomalies(Authentication authentication) {
        Long userId = getUserId(authentication);
        List<Anomaly> anomalies = mlService.detectAnomalies(userId);
        List<AnomalyResponse> response = anomalies.stream()
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
        return ResponseEntity.ok(response);
    }

    @GetMapping("/anomalies")
    public ResponseEntity<List<AnomalyResponse>> getAnomalies(Authentication authentication) {
        Long userId = getUserId(authentication);
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        List<Anomaly> anomalies = anomalyRepository.findAllByDeviceInOrderByTimestampDesc(devices);
        List<AnomalyResponse> response = anomalies.stream()
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
        return ResponseEntity.ok(response);
    }

    private Long getUserId(Authentication authentication) {
        com.energy.monitoring.entity.User user =
                (com.energy.monitoring.entity.User) authentication.getPrincipal();
        return user.getId();
    }
}
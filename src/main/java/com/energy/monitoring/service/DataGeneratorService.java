package com.energy.monitoring.service;

import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.Reading;
import com.energy.monitoring.generator.PatternGenerator;
import com.energy.monitoring.repository.DeviceRepository;
import com.energy.monitoring.repository.ReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataGeneratorService {

    private static final int DEMO_HISTORY_DAYS = 14;
    private static final String GENERATOR_SOURCE = "GENERATOR";
    private static final String SIMULATED_ANOMALY_SOURCE = "SIMULATED_ANOMALY";

    private final MLService mlService;
    private final DeviceRepository deviceRepository;
    private final ReadingRepository readingRepository;
    private final PatternGenerator patternGenerator;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedHistoricalData() {
        List<Device> devices = deviceRepository.findAllByActiveTrue();
        if (devices.isEmpty()) {
            log.info("DataGenerator: no active devices found, historical readings were not generated.");
            return;
        }

        int created = seedMissingReadings(devices, DEMO_HISTORY_DAYS);
        log.info("DataGenerator: startup gap fill created {} readings", created);
    }

    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void generateCurrentReading() {
        List<Device> devices = deviceRepository.findAllByActiveTrue();
        LocalDateTime now = alignToTenMinuteStep(LocalDateTime.now());

        List<Reading> readings = devices.stream()
                .filter(device -> !readingRepository.existsByDeviceAndTimestamp(device, now))
                .map(device -> Reading.builder()
                        .device(device)
                        .timestamp(now)
                        .powerConsumption(patternGenerator.generate(device, now))
                        .source(GENERATOR_SOURCE)
                        .build())
                .toList();

        readingRepository.saveAll(readings);
        log.info("DataGenerator: generated {} current readings at {}", readings.size(), now);

        List<Long> userIds = deviceRepository.findDistinctUserIdsByActiveTrue();
        userIds.forEach(userId -> {
            try {
                mlService.detectAnomalies(userId);
                mlService.generateScheduledAnomalies(userId);
            } catch (Exception e) {
                log.warn("DataGenerator: anomaly processing for userId={} failed: {}", userId, e.getMessage());
            }
        });
    }

    @Async
    @Transactional
    public void seedForDevice(Long deviceId) {
        deviceRepository.findById(deviceId).ifPresent(device -> {
            int created = seedMissingReadings(List.of(device), DEMO_HISTORY_DAYS);
            log.info("DataGenerator: new device seed created {} readings for '{}'", created, device.getName());
        });
    }

    @Async
    @Transactional
    public void seedForUser(Long userId) {
        int created = ensureRecentDataForUser(userId);
        log.info("DataGenerator: user seed created {} readings for userId={}", created, userId);
    }

    @Transactional
    public int ensureRecentDataForUser(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return 0;
        }

        return seedMissingReadings(devices, DEMO_HISTORY_DAYS);
    }

    private int seedMissingReadings(List<Device> devices, int days) {
        LocalDateTime now = alignToTenMinuteStep(LocalDateTime.now());
        LocalDateTime from = now.minusDays(days);
        int created = 0;

        for (Device device : devices) {
            Set<LocalDateTime> existing = new HashSet<>(readingRepository
                    .findTimestampsByDeviceAndTimestampBetweenExcludingSource(
                            device,
                            from,
                            now,
                            SIMULATED_ANOMALY_SOURCE
                    ));
            List<Reading> batch = new ArrayList<>();
            LocalDateTime cursor = from;

            while (!cursor.isAfter(now)) {
                if (!existing.contains(cursor)) {
                    batch.add(Reading.builder()
                            .device(device)
                            .timestamp(cursor)
                            .powerConsumption(patternGenerator.generate(device, cursor))
                            .source(GENERATOR_SOURCE)
                            .build());
                }
                cursor = cursor.plusMinutes(10);
            }

            if (!batch.isEmpty()) {
                readingRepository.saveAll(batch);
                created += batch.size();
                log.info("DataGenerator: saved {} generated readings for '{}'", batch.size(), device.getName());
            }
        }

        return created;
    }

    private LocalDateTime alignToTenMinuteStep(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MINUTES)
                .withMinute((time.getMinute() / 10) * 10)
                .withSecond(0)
                .withNano(0);
    }
}

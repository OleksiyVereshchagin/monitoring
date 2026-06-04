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

    private static final int STARTUP_HISTORY_DAYS = 14;
    private static final int SCHEDULE_GAP_HOURS = 24;
    private static final String GENERATOR_SOURCE = "GENERATOR";

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

        LocalDateTime now = alignToTenMinuteStep(LocalDateTime.now());
        LocalDateTime from = now.minusDays(STARTUP_HISTORY_DAYS);
        int created = generateMissingReadings(devices, from, now);
        log.info("DataGenerator: startup gap fill created {} readings", created);
    }

    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void generateCurrentReading() {
        List<Device> devices = deviceRepository.findAllByActiveTrue();
        LocalDateTime now = alignToTenMinuteStep(LocalDateTime.now());
        LocalDateTime from = now.minusHours(SCHEDULE_GAP_HOURS);

        int created = generateMissingReadings(devices, from, now);
        log.info("DataGenerator: generated {} missing readings up to {}", created, now);

        List<Long> userIds = deviceRepository.findDistinctUserIdsByActiveTrue();
        userIds.forEach(userId -> {
            try {
                mlService.detectAnomalies(userId);
            } catch (Exception e) {
                log.warn("DataGenerator: anomaly processing for userId={} failed: {}", userId, e.getMessage());
            }
        });
    }

    @Async
    @Transactional
    public void seedForUser(Long userId) {
        int created = ensureRecentDataForUser(userId);
        log.info("DataGenerator: seed for userId={} created {} readings", userId, created);
    }

    @Transactional
    public int ensureRecentDataForUser(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return 0;
        }

        LocalDateTime now = alignToTenMinuteStep(LocalDateTime.now());
        LocalDateTime from = now.minusHours(SCHEDULE_GAP_HOURS);
        return generateMissingReadings(devices, from, now);
    }

    private int generateMissingReadings(List<Device> devices, LocalDateTime from, LocalDateTime to) {
        int created = 0;

        for (Device device : devices) {
            Set<LocalDateTime> existing = new HashSet<>(
                    readingRepository.findTimestampsByDeviceAndTimestampBetween(device, from, to)
            );
            List<Reading> batch = new ArrayList<>();
            LocalDateTime cursor = from;

            while (!cursor.isAfter(to)) {
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

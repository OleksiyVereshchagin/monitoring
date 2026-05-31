package com.energy.monitoring.service;

import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.Reading;
import com.energy.monitoring.generator.PatternGenerator;
import com.energy.monitoring.repository.DeviceRepository;
import com.energy.monitoring.repository.ReadingRepository;
import com.energy.monitoring.service.MLService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataGeneratorService {

    private final MLService mlService;
    private final DeviceRepository deviceRepository;
    private final ReadingRepository readingRepository;
    private final PatternGenerator patternGenerator;
    private final java.util.Random random = new java.util.Random();

    // Запускається один раз при старті — заповнює 60 днів історії
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedHistoricalData() {
        List<Device> devices = deviceRepository.findAll();
        if (devices.isEmpty()) {
            log.warn("DataGenerator: пристроїв не знайдено, історію не заповнено.");
            return;
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        LocalDateTime from = now.minusDays(60);

        for (Device device : devices) {
            // Перевірка: якщо вже є readings для цього device — пропускаємо
            boolean alreadyHasData = readingRepository
                    .existsByDeviceAndTimestampAfter(device, from);
            if (alreadyHasData) {
                log.info("DataGenerator: пристрій '{}' вже має дані, пропускаємо.", device.getName());
                continue;
            }

            List<Reading> batch = new ArrayList<>();
            LocalDateTime cursor = from;

            while (!cursor.isAfter(now)) {
                BigDecimal power = patternGenerator.generate(device, cursor);
                batch.add(Reading.builder()
                        .device(device)
                        .timestamp(cursor)
                        .powerConsumption(power)
                        .source("GENERATOR")
                        .build());
                cursor = cursor.plusMinutes(10);
            }

            readingRepository.saveAll(batch);
            log.info("DataGenerator: збережено {} записів для '{}'", batch.size(), device.getName());
        }
    }

    // Щогодини генерує поточний показник для всіх пристроїв
    @Scheduled(cron = "0 */10 * * * *") // кожні 10 хвилин
    @Transactional
    public void generateCurrentReading() {
        List<Device> devices = deviceRepository.findAllByActiveTrue();
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
                .withMinute((LocalDateTime.now().getMinute() / 10) * 10);

        List<Reading> readings = devices.stream()
                .map(device -> Reading.builder()
                        .device(device)
                        .timestamp(now.plusSeconds(random.nextInt(50)))
                        .powerConsumption(patternGenerator.generate(device, now))
                        .source("GENERATOR")
                        .build())
                .toList();

        readingRepository.saveAll(readings);
        log.info("DataGenerator: згенеровано {} поточних показників о {}", readings.size(), now);

        List<Long> userIds = deviceRepository.findDistinctUserIdsByActiveTrue();
        userIds.forEach(userId -> {
            try {
                mlService.detectAnomalies(userId);
            } catch (Exception e) {
                log.warn("DataGenerator: детекція для userId={} не вдалася: {}", userId, e.getMessage());
            }
        });
    }

    @Async
    @Transactional
    public void seedForUser(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
                .withMinute((LocalDateTime.now().getMinute() / 10) * 10);
        LocalDateTime from = now.minusDays(60);

        for (Device device : devices) {
            boolean alreadyHasData = readingRepository.existsByDeviceAndTimestampAfter(device, from);
            if (alreadyHasData) {
                continue;
            }

            List<Reading> batch = new ArrayList<>();
            LocalDateTime cursor = from;

            while (!cursor.isAfter(now)) {
                batch.add(Reading.builder()
                        .device(device)
                        .timestamp(cursor.plusSeconds(random.nextInt(50)))
                        .powerConsumption(patternGenerator.generate(device, cursor))
                        .source("GENERATOR")
                        .build());
                cursor = cursor.plusMinutes(10);
            }

            readingRepository.saveAll(batch);
            log.info("DataGenerator: seed для нового пристрою '{}' userId={}", device.getName(), userId);
        }
    }



}
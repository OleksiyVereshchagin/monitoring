package com.energy.monitoring.service;

import com.energy.monitoring.entity.Anomaly;
import com.energy.monitoring.entity.AnomalyType;
import com.energy.monitoring.entity.ActivityLevel;
import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.HouseholdType;
import com.energy.monitoring.entity.PresenceMode;
import com.energy.monitoring.entity.Reading;
import com.energy.monitoring.entity.SimulationProfile;
import com.energy.monitoring.repository.AnomalyRepository;
import com.energy.monitoring.repository.DeviceRepository;
import com.energy.monitoring.repository.ReadingRepository;
import com.energy.monitoring.repository.SimulationProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class MLService {

    private static final int WINDOW_SIZE = 144;   // 24 год × 6 (кожні 10 хв)
    private static final int FORECAST_SIZE = 144;  // прогноз на 24 год

    private final DeviceRepository deviceRepository;
    private final ReadingRepository readingRepository;
    private final AnomalyRepository anomalyRepository;
    private final SimulationProfileRepository simulationProfileRepository;

    private MultiLayerNetwork model;
    private double minValue = 0.0;
    private double maxValue = 1.0;

    private String getModelPath(Long userId) {
        return "src/main/resources/model/lstm_model_" + userId + ".zip";
    }

    private String getNormPath(Long userId) {
        return "src/main/resources/model/normalization_" + userId + ".properties";
    }

    // ── Навчання ────────────────────────────────────────────────────────────

    public void train(Long userId) {
        log.info("MLService: починаємо навчання для userId={}", userId);

        List<Double> series = loadAggregatedSeries(userId);
        if (series.size() < WINDOW_SIZE * 2) {
            throw new IllegalStateException(
                    "Недостатньо даних для навчання. Потрібно мінімум " + (WINDOW_SIZE * 2) + " точок.");
        }

        minValue = series.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        maxValue = series.stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double[] normalized = normalize(series);

        int sampleCount = normalized.length - WINDOW_SIZE - FORECAST_SIZE;
        INDArray input  = Nd4j.zeros(sampleCount, 1, WINDOW_SIZE);
        INDArray labels = Nd4j.zeros(sampleCount, 1, FORECAST_SIZE);

        for (int i = 0; i < sampleCount; i++) {
            for (int t = 0; t < WINDOW_SIZE; t++) {
                input.putScalar(new int[]{i, 0, t}, normalized[i + t]);
            }
            for (int t = 0; t < FORECAST_SIZE; t++) {
                labels.putScalar(new int[]{i, 0, t}, normalized[i + WINDOW_SIZE + t]);
            }
        }

        model = buildModel();
        int epochs = 7;
        int batchSize = 32;

        for (int epoch = 0; epoch < epochs; epoch++) {
            for (int i = 0; i < sampleCount; i += batchSize) {
                int end = Math.min(i + batchSize, sampleCount);
                INDArray batchInput = input.get(
                        org.nd4j.linalg.indexing.NDArrayIndex.interval(i, end),
                        org.nd4j.linalg.indexing.NDArrayIndex.all(),
                        org.nd4j.linalg.indexing.NDArrayIndex.all()
                );
                INDArray batchLabels = labels.get(
                        org.nd4j.linalg.indexing.NDArrayIndex.interval(i, end),
                        org.nd4j.linalg.indexing.NDArrayIndex.all(),
                        org.nd4j.linalg.indexing.NDArrayIndex.all()
                );
                model.fit(batchInput, batchLabels);
            }
            if (epoch % 2 == 0) {
                log.info("MLService: epoch {}/{}, loss={}", epoch, epochs, model.score());
            }
        }

        saveModel(userId);
        log.info("MLService: навчання завершено, модель збережено для userId={}", userId);
    }

    // ── Прогноз ─────────────────────────────────────────────────────────────

    public List<Double> forecast(Long userId) {
        return forecast(userId, null);
    }

    public List<Double> forecast(Long userId, Long householdId) {
        try {
            loadModelIfNeeded(userId);

            List<Double> series = loadAggregatedSeries(userId, householdId);
            if (series.size() < WINDOW_SIZE) {
                throw new IllegalStateException("Недостатньо даних для прогнозу.");
            }

            List<Double> window = series.subList(series.size() - WINDOW_SIZE, series.size());
            double[] normalized = normalize(window);

            INDArray input = Nd4j.zeros(1, 1, WINDOW_SIZE);
            for (int t = 0; t < WINDOW_SIZE; t++) {
                input.putScalar(new int[]{0, 0, t}, normalized[t]);
            }

            INDArray output = model.output(input);

            List<Double> result = new java.util.ArrayList<>();
            for (int t = 0; t < FORECAST_SIZE; t++) {
                double val = output.getDouble(0, 0, t);
                result.add(safeForecastValue(denormalize(val)));
            }

            return result;
        } catch (RuntimeException e) {
            log.warn("MLService: LSTM forecast unavailable for userId={}, householdId={}, using stable fallback: {}",
                    userId, householdId, e.getMessage());
            return fallbackForecast(userId, householdId);
        }
    }

    public ForecastResult forecast(Long userId, Long householdId, int days) {
        int forecastDays = normalizeForecastDays(days);
        if (forecastDays == 1) {
            return new ForecastResult(1, "LSTM_24H", forecast(userId, householdId));
        }

        List<Double> oneDayForecast = forecast(userId, householdId);
        return new ForecastResult(
                forecastDays,
                "HISTORICAL_PATTERN",
                historicalPatternForecast(userId, householdId, forecastDays, totalKwh(oneDayForecast))
        );
    }

    // ── Розпізнання аномалій ────────────────────────────────────────────────────

    public List<Anomaly> detectAnomalies(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return List.of();
        }

        List<Anomaly> detected = new ArrayList<>();
        LocalDateTime from = LocalDateTime.now().minusDays(1);

        for (Device device : devices) {
            // Отримуємо readings за останні 24 години
            List<Reading> recentReadings = readingRepository
                    .findByDeviceAndTimestampAfterOrderByTimestampAsc(device, from)
                    .stream()
                    .filter(reading -> !"SIMULATED_ANOMALY".equals(reading.getSource()))
                    .toList();

            if (recentReadings.isEmpty()) {
                continue;
            }

            // Середнє і стандартне відхилення за останні 14 днів
            LocalDateTime statsFrom = LocalDateTime.now().minusDays(14);
            List<Reading> historyReadings = readingRepository
                    .findByDeviceAndTimestampAfterOrderByTimestampAsc(device, statsFrom)
                    .stream()
                    .filter(reading -> !"SIMULATED_ANOMALY".equals(reading.getSource()))
                    .toList();

            if (historyReadings.size() < 10) {
                continue;
            }

            double mean = historyReadings.stream()
                    .mapToDouble(r -> r.getPowerConsumption().doubleValue())
                    .average()
                    .orElse(0);

            double stdDev = Math.sqrt(historyReadings.stream()
                    .mapToDouble(r -> Math.pow(r.getPowerConsumption().doubleValue() - mean, 2))
                    .average()
                    .orElse(0));

            // Перевіряємо кожен recent reading
            for (Reading reading : recentReadings) {
                double actual = reading.getPowerConsumption().doubleValue();

                // Пропускаємо якщо аномалія вже зафіксована
                if (anomalyRepository.existsByDeviceAndTimestamp(device, reading.getTimestamp())) {
                    continue;
                }

                // Z-score: відхилення більше 3σ → аномалія
                if (stdDev > 0) {
                    double zScore = Math.abs(actual - mean) / stdDev;
                    if (zScore > 3.0) {
                        AnomalyType type = actual > mean ? AnomalyType.SPIKE : AnomalyType.DROP;
                        double deviationPercent = Math.abs(actual - mean) / mean * 100;

                        Anomaly anomaly = Anomaly.builder()
                                .device(device)
                                .timestamp(reading.getTimestamp())
                                .actualValue(reading.getPowerConsumption())
                                .expectedValue(BigDecimal.valueOf(mean).setScale(4, RoundingMode.HALF_UP))
                                .deviationPercent(BigDecimal.valueOf(deviationPercent).setScale(2, RoundingMode.HALF_UP))
                                .type(type)
                                .build();

                        detected.add(anomalyRepository.save(anomaly));
                        log.info("MLService: аномалія '{}' для '{}' о {} (z={})",
                                type, device.getName(), reading.getTimestamp(), String.format("%.2f", zScore));
                    }
                }
            }
        }

        log.info("MLService: виявлено {} аномалій", detected.size());
        return detected;
    }

    // ── Симуляція аномалійgenerateCurrentReading ────────────────────────────────────────────────────


    public Anomaly simulateAnomaly(Long userId) {
        return simulateAnomalyForDevices(deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId));
    }

    public List<Anomaly> ensureDailyDisplayAnomalies(Long userId) {
        List<Device> activeDevices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .filter(device -> Boolean.TRUE.equals(device.getActive()))
                .toList();
        if (activeDevices.isEmpty()) {
            return List.of();
        }

        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        long existingToday = anomalyRepository.countByDeviceInAndTimestampBetween(activeDevices, dayStart, dayEnd);

        List<LocalDateTime> plannedTimes = plannedDisplayAnomalyTimes(userId, today);
        LocalDateTime now = LocalDateTime.now();
        int dueCount = (int) plannedTimes.stream()
                .filter(timestamp -> !timestamp.isAfter(now))
                .count();
        int missing = Math.max(0, dueCount - (int) existingToday);
        if (missing == 0) {
            return List.of();
        }

        List<Anomaly> recentAnomalies = new ArrayList<>(anomalyRepository.findAllByDeviceInOrderByTimestampDesc(activeDevices));
        List<Anomaly> created = new ArrayList<>();
        for (int i = 0; i < missing; i++) {
            LocalDateTime timestamp = plannedTimes.get(Math.max(0, dueCount - missing + i));
            Device device = chooseDisplayAnomalyDevice(activeDevices, recentAnomalies);
            Anomaly anomaly = createSimulatedAnomaly(device, chooseSimulatedAnomalyType(device, recentAnomalies), timestamp);
            created.add(anomaly);
            recentAnomalies.add(0, anomaly);
        }

        return created;
    }

    public Anomaly simulateAnomalyForHousehold(Long userId, Long householdId) {
        return simulateAnomalyForDevices(deviceRepository.findAllByUserIdAndHouseholdIdOrderByCreatedAtDesc(userId, householdId));
    }

    private Anomaly simulateAnomalyForDevices(List<Device> devices) {
        List<Device> activeDevices = devices
                .stream()
                .filter(device -> Boolean.TRUE.equals(device.getActive()))
                .toList();

        if (activeDevices.isEmpty()) {
            throw new RuntimeException("Пристроїв не знайдено.");
        }

        List<Anomaly> recentAnomalies = anomalyRepository.findAllByDeviceInOrderByTimestampDesc(activeDevices);
        Long latestDeviceId = recentAnomalies.isEmpty()
                ? null
                : recentAnomalies.get(0).getDevice().getId();

        List<Device> candidates = activeDevices;
        if (activeDevices.size() > 1 && latestDeviceId != null) {
            candidates = activeDevices.stream()
                    .filter(device -> !device.getId().equals(latestDeviceId))
                    .toList();
        }

        Device device = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        return createSimulatedAnomaly(device, chooseSimulatedAnomalyType(device, recentAnomalies));
    }

    private Device chooseDisplayAnomalyDevice(List<Device> activeDevices, List<Anomaly> recentAnomalies) {
        Long latestDeviceId = recentAnomalies.isEmpty()
                ? null
                : recentAnomalies.get(0).getDevice().getId();

        List<Device> candidates = activeDevices;
        if (activeDevices.size() > 1 && latestDeviceId != null) {
            candidates = activeDevices.stream()
                    .filter(device -> !device.getId().equals(latestDeviceId))
                    .toList();
        }

        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    public Anomaly simulateAnomaly(Long deviceId, Long userId) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new RuntimeException("Пристрій не знайдено"));

        return createSimulatedAnomaly(device, chooseSimulatedAnomalyType(device, List.of()));
    }

    private Anomaly createSimulatedAnomaly(Device device, AnomalyType type) {
        return createSimulatedAnomaly(device, type, LocalDateTime.now());
    }

    private Anomaly createSimulatedAnomaly(Device device, AnomalyType type, LocalDateTime timestamp) {
        BigDecimal expected = estimateExpectedValue(device);
        BigDecimal actual = simulateActualValue(device, expected, type);
        BigDecimal safeExpected = expected.compareTo(BigDecimal.ZERO) > 0 ? expected : BigDecimal.ONE;
        BigDecimal deviationPercent = actual.subtract(expected)
                .abs()
                .divide(safeExpected, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        Anomaly anomaly = Anomaly.builder()
                .device(device)
                .timestamp(timestamp)
                .actualValue(actual)
                .expectedValue(expected)
                .deviationPercent(deviationPercent)
                .type(type)
                .build();

        Anomaly saved = anomalyRepository.save(anomaly);
        log.info("MLService: simulated {} anomaly for '{}'", type, device.getName());
        return saved;
    }

    private AnomalyType chooseSimulatedAnomalyType(Device device, List<Anomaly> recentAnomalies) {
        return recentAnomalies.stream()
                .filter(anomaly -> anomaly.getDevice().getId().equals(device.getId()))
                .findFirst()
                .map(anomaly -> anomaly.getType() == AnomalyType.SPIKE ? AnomalyType.DROP : AnomalyType.SPIKE)
                .orElseGet(() -> ThreadLocalRandom.current().nextBoolean() ? AnomalyType.SPIKE : AnomalyType.DROP);
    }

    private List<LocalDateTime> plannedDisplayAnomalyTimes(Long userId, LocalDate date) {
        Random random = new Random(userId * 31 + date.toEpochDay() * 17);
        int targetCount = 3 + random.nextInt(5);
        List<LocalDateTime> times = new ArrayList<>();

        for (int i = 0; i < targetCount; i++) {
            int secondOfDay = 5 * 60 + random.nextInt((23 * 60 * 60) - (10 * 60));
            if (secondOfDay % 60 == 0) {
                secondOfDay += 17;
            }
            times.add(LocalDateTime.of(date, LocalTime.ofSecondOfDay(secondOfDay)));
        }

        return times.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    // ── Допоміжні методи ────────────────────────────────────────────────────

    private List<Double> loadAggregatedSeries(Long userId) {
        return loadAggregatedSeries(userId, null);
    }

    private List<Double> loadAggregatedSeries(Long userId, Long householdId) {
        List<Device> devices = householdId != null
                ? deviceRepository.findAllByUserIdAndHouseholdIdOrderByCreatedAtDesc(userId, householdId)
                : deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            throw new IllegalStateException("Пристроїв не знайдено.");
        }

        List<Double> series = (householdId != null
                ? readingRepository.findAggregatedByUserIdAndHouseholdIdOrderByTimestamp(userId, householdId, "SIMULATED_ANOMALY")
                : readingRepository.findAggregatedByUserIdOrderByTimestamp(userId, "SIMULATED_ANOMALY"))
                .stream()
                .map(Double::valueOf)
                .toList();

        // Тільки останні 14 днів = 2016 точок
        int maxPoints = 14 * 144;
        if (series.size() > maxPoints) {
            series = series.subList(series.size() - maxPoints, series.size());
        }

        return series;
    }

    private BigDecimal estimateExpectedValue(Device device) {
        LocalDateTime statsFrom = LocalDateTime.now().minusDays(14);
        List<Reading> historyReadings = readingRepository
                .findByDeviceAndTimestampAfterOrderByTimestampAsc(device, statsFrom)
                .stream()
                .filter(reading -> !"SIMULATED_ANOMALY".equals(reading.getSource()))
                .toList();

        if (historyReadings.size() >= 10) {
            double mean = historyReadings.stream()
                    .mapToDouble(reading -> reading.getPowerConsumption().doubleValue())
                    .average()
                    .orElse(0);
            return BigDecimal.valueOf(Math.max(mean, 0.01)).setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal nominal = device.getNominalPower() != null
                ? device.getNominalPower()
                : BigDecimal.valueOf(1.0);
        return nominal.max(BigDecimal.valueOf(0.01)).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal simulateActualValue(Device device, BigDecimal expected, AnomalyType type) {
        boolean heavy = isHeavyDevice(device);
        double maxDeviation = heavy ? 60.0 : type == AnomalyType.SPIKE ? 160.0 : 85.0;
        double minDeviation = heavy ? 12.0 : 18.0;
        double deviation = ThreadLocalRandom.current().nextDouble(minDeviation, maxDeviation);
        double factor = type == AnomalyType.SPIKE
                ? 1.0 + deviation / 100.0
                : Math.max(0.08, 1.0 - deviation / 100.0);
        return expected.multiply(BigDecimal.valueOf(factor)).setScale(4, RoundingMode.HALF_UP);
    }

    private boolean isHeavyDevice(Device device) {
        if (device.getNominalPower() != null && device.getNominalPower().doubleValue() >= 1.2) {
            return true;
        }

        return switch (device.getType()) {
            case AC, HEATER, BOILER, WASHING_MACHINE, DRYER, DISHWASHER, OVEN, STOVE,
                 WATER_PUMP, EV_CHARGER, BATTERY_STORAGE -> true;
            default -> false;
        };
    }

    private List<Double> fallbackForecast(Long userId) {
        return fallbackForecast(userId, null);
    }

    private List<Double> fallbackForecast(Long userId, Long householdId) {
        try {
            List<Double> series = loadAggregatedSeries(userId, householdId);
            if (series.isEmpty()) {
                return zeroForecast();
            }

            if (series.size() >= FORECAST_SIZE) {
                return series.subList(series.size() - FORECAST_SIZE, series.size())
                        .stream()
                        .map(this::safeForecastValue)
                        .toList();
            }

            double average = series.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
            return repeatedForecast(average);
        } catch (RuntimeException e) {
            return zeroForecast();
        }
    }

    private List<Double> repeatedForecast(double value) {
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < FORECAST_SIZE; i++) {
            result.add(safeForecastValue(value));
        }
        return result;
    }

    private List<Double> zeroForecast() {
        return repeatedForecast(0.0);
    }

    private List<Double> historicalPatternForecast(Long userId, Long householdId, int days, double oneDayForecastKwh) {
        try {
            List<Double> series = loadAggregatedSeries(userId, householdId);
            if (series.isEmpty()) {
                return repeatedForecast(0.0, days * FORECAST_SIZE);
            }

            SimulationProfile profile = resolveForecastProfile(userId, householdId);
            double realisticDailyCap = realisticDailyKwhCap(userId, householdId, profile);
            double baseDailyKwh = Math.min(baselineDailyKwh(series, oneDayForecastKwh), realisticDailyCap);
            LocalDate startDate = LocalDate.now().plusDays(1);
            List<Double> result = new ArrayList<>();
            for (int day = 0; day < days; day++) {
                LocalDate date = startDate.plusDays(day);
                List<Double> dayShape = new ArrayList<>();
                for (int slot = 0; slot < FORECAST_SIZE; slot++) {
                    dayShape.add(safeForecastValue(historicalPatternValue(series, date, slot)));
                }

                double targetDailyKwh = targetDailyKwh(baseDailyKwh, profile, date, userId, day);
                result.addAll(scaleDayToKwh(dayShape, targetDailyKwh));
            }

            return clampMultiDayForecast(result, series, days, oneDayForecastKwh, realisticDailyCap);
        } catch (RuntimeException e) {
            return repeatedForecast(0.0, days * FORECAST_SIZE);
        }
    }

    private double historicalPatternValue(List<Double> series, LocalDate targetDate, int slot) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (int day = 1; day <= 14; day++) {
            int index = series.size() - (day * FORECAST_SIZE) + slot;
            if (index >= 0 && index < series.size()) {
                LocalDate historicalDate = targetDate.minusDays(day);
                double weight = historicalDate.getDayOfWeek() == targetDate.getDayOfWeek() ? 1.8 : 1.0;
                weightedSum += series.get(index) * weight;
                totalWeight += weight;
            }
        }

        if (totalWeight <= 0) {
            return series.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
        }

        return weightedSum / totalWeight;
    }

    private SimulationProfile resolveForecastProfile(Long userId, Long householdId) {
        if (householdId == null) {
            return null;
        }

        return simulationProfileRepository
                .findFirstByUserIdAndHouseholdIdOrderByIdDesc(userId, householdId)
                .orElse(null);
    }

    private double baselineDailyKwh(List<Double> history, double oneDayForecastKwh) {
        double historicalDailyKwh = averageDailyKwh(history);
        if (historicalDailyKwh <= 0) {
            return Math.max(0.0, oneDayForecastKwh);
        }
        if (oneDayForecastKwh <= 0) {
            return historicalDailyKwh;
        }
        return (oneDayForecastKwh * 0.65) + (historicalDailyKwh * 0.35);
    }

    private double realisticDailyKwhCap(Long userId, Long householdId, SimulationProfile profile) {
        long activeDevices = householdId != null
                ? deviceRepository.findAllByUserIdAndHouseholdIdOrderByCreatedAtDesc(userId, householdId).stream()
                        .filter(device -> Boolean.TRUE.equals(device.getActive()))
                        .count()
                : deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                        .filter(device -> Boolean.TRUE.equals(device.getActive()))
                        .count();

        HouseholdType type = profile != null && profile.getHousehold() != null
                ? profile.getHousehold().getType()
                : HouseholdType.APARTMENT;

        double base = switch (type) {
            case HOUSE -> 10.5;
            case OFFICE -> 11.5;
            case COTTAGE -> 5.5;
            case APARTMENT -> 7.8;
            default -> 7.0;
        };

        double deviceFactor = Math.min(2.8, Math.max(0.0, activeDevices - 4) * 0.35);
        double profileFactor = 1.0;
        if (profile != null) {
            int occupants = profile.getOccupants() != null ? profile.getOccupants() : 2;
            profileFactor += Math.min(0.18, Math.max(0, occupants - 2) * 0.04);
            if (profile.getAreaM2() != null) {
                profileFactor += Math.min(0.14, Math.max(0.0, profile.getAreaM2().doubleValue() - 55.0) * 0.002);
            }
            profileFactor *= activityDayFactor(profile);
        }

        return (base + deviceFactor) * profileFactor;
    }

    private double targetDailyKwh(double baseDailyKwh, SimulationProfile profile, LocalDate date, Long userId, int dayOffset) {
        double factor = objectDayFactor(profile, date)
                * activityDayFactor(profile)
                * presenceDayFactor(profile, date)
                * deterministicNoise(userId, date, dayOffset);
        return Math.max(0.0, baseDailyKwh * factor);
    }

    private double objectDayFactor(SimulationProfile profile, LocalDate date) {
        HouseholdType type = profile != null && profile.getHousehold() != null
                ? profile.getHousehold().getType()
                : HouseholdType.APARTMENT;
        boolean activeDay = isProfileActiveDay(profile, date.getDayOfWeek());

        return switch (type) {
            case OFFICE -> activeDay ? 1.12 : 0.32;
            case COTTAGE -> activeDay ? 1.18 : 0.22;
            case HOUSE -> activeDay ? 1.10 : 0.96;
            case APARTMENT -> activeDay ? 1.08 : 0.94;
            default -> activeDay ? 1.05 : 0.9;
        };
    }

    private double activityDayFactor(SimulationProfile profile) {
        if (profile == null || profile.getActivityLevel() == null) {
            return 1.0;
        }

        return switch (profile.getActivityLevel()) {
            case ECONOMY -> 0.88;
            case NORMAL -> 1.0;
            case ACTIVE -> 1.12;
        };
    }

    private double presenceDayFactor(SimulationProfile profile, LocalDate date) {
        if (profile == null || profile.getPresenceMode() == null) {
            return 1.0;
        }

        boolean activeDay = isProfileActiveDay(profile, date.getDayOfWeek());
        HouseholdType type = profile.getHousehold() != null ? profile.getHousehold().getType() : HouseholdType.APARTMENT;

        if (type == HouseholdType.OFFICE) {
            return activeDay ? 1.05 : 0.72;
        }
        if (type == HouseholdType.COTTAGE) {
            return switch (profile.getPresenceMode()) {
                case OFTEN_HOME -> activeDay ? 1.12 : 0.42;
                case PARTLY_HOME, CUSTOM -> activeDay ? 1.0 : 0.26;
                case STANDARD_WORKDAY -> activeDay ? 0.9 : 0.18;
            };
        }

        return switch (profile.getPresenceMode()) {
            case OFTEN_HOME -> activeDay ? 1.08 : 1.02;
            case PARTLY_HOME -> activeDay ? 1.04 : 0.96;
            case CUSTOM -> activeDay ? 1.03 : 0.94;
            case STANDARD_WORKDAY -> activeDay ? 1.08 : 0.92;
        };
    }

    private boolean isProfileActiveDay(SimulationProfile profile, DayOfWeek day) {
        String activeDays = profile != null && profile.getWeekendDays() != null
                ? profile.getWeekendDays()
                : "SATURDAY,SUNDAY";
        return !"NONE".equalsIgnoreCase(activeDays) && activeDays.contains(day.name());
    }

    private double deterministicNoise(Long userId, LocalDate date, int dayOffset) {
        Random random = new Random((userId != null ? userId : 0L) * 97 + date.toEpochDay() * 37 + dayOffset * 11L);
        return 0.94 + random.nextDouble() * 0.12;
    }

    private List<Double> scaleDayToKwh(List<Double> values, double targetKwh) {
        double currentKwh = totalKwh(values);
        if (currentKwh <= 0 || targetKwh <= 0) {
            return values;
        }

        double scale = targetKwh / currentKwh;
        return values.stream()
                .map(value -> safeForecastValue(value * scale))
                .toList();
    }

    private List<Double> clampMultiDayForecast(
            List<Double> forecast,
            List<Double> history,
            int days,
            double oneDayForecastKwh,
            double realisticDailyCap
    ) {
        double historicalDailyKwh = averageDailyKwh(history);
        double forecastTotalKwh = totalKwh(forecast);
        if (historicalDailyKwh <= 0 || forecastTotalKwh <= 0) {
            return forecast;
        }

        double dailyLimit = oneDayForecastKwh > 0
                ? Math.min(historicalDailyKwh * 1.08, oneDayForecastKwh * 1.08)
                : historicalDailyKwh;
        dailyLimit = Math.min(dailyLimit, realisticDailyCap * 1.12);
        double maxTotal = dailyLimit * days;
        if (forecastTotalKwh <= maxTotal) {
            return forecast;
        }

        double scale = maxTotal / forecastTotalKwh;
        return forecast.stream()
                .map(value -> safeForecastValue(value * scale))
                .toList();
    }

    private double averageDailyKwh(List<Double> values) {
        int days = Math.max(1, Math.min(14, values.size() / FORECAST_SIZE));
        int points = Math.min(values.size(), days * FORECAST_SIZE);
        List<Double> recent = values.subList(values.size() - points, values.size());
        return totalKwh(recent) / days;
    }

    private double totalKwh(List<Double> values) {
        return values.stream()
                .mapToDouble(Double::doubleValue)
                .sum() * (10.0 / 60.0);
    }

    private List<Double> repeatedForecast(double value, int points) {
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            result.add(safeForecastValue(value));
        }
        return result;
    }

    private int normalizeForecastDays(int days) {
        if (days <= 1) {
            return 1;
        }
        if (days <= 5) {
            return 5;
        }
        return 7;
    }

    private double safeForecastValue(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
    }

    public record ForecastResult(int days, String source, List<Double> values) {
    }

    private MultiLayerNetwork buildModel() {
        MultiLayerConfiguration config = new NeuralNetConfiguration.Builder()
                .updater(new Adam(0.001))
                .weightInit(WeightInit.XAVIER)
                .list()
                .layer(new LSTM.Builder()
                        .nIn(1).nOut(64)
                        .activation(Activation.TANH)
                        .build())
                .layer(new RnnOutputLayer.Builder(LossFunctions.LossFunction.MSE)
                        .nIn(64).nOut(1)
                        .activation(Activation.IDENTITY)
                        .build())
                .build();

        MultiLayerNetwork net = new MultiLayerNetwork(config);
        net.init();
        return net;
    }

    private double[] normalize(List<Double> values) {
        return values.stream()
                .mapToDouble(v -> (v - minValue) / (maxValue - minValue + 1e-8))
                .toArray();
    }

    private double[] normalize(double[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (values[i] - minValue) / (maxValue - minValue + 1e-8);
        }
        return result;
    }

    private double denormalize(double value) {
        return value * (maxValue - minValue) + minValue;
    }

    private void saveModel(Long userId) {
        try {
            File file = new File(getModelPath(userId));
            file.getParentFile().mkdirs();
            ModelSerializer.writeModel(model, file, true);

            java.util.Properties props = new java.util.Properties();
            props.setProperty("minValue", String.valueOf(minValue));
            props.setProperty("maxValue", String.valueOf(maxValue));
            try (var out = new java.io.FileOutputStream(getNormPath(userId))) {
                props.store(out, "Normalization params");
            }
        } catch (IOException e) {
            log.error("MLService: не вдалося зберегти модель для userId={}", userId, e);
        }
    }

    private void loadModelIfNeeded(Long userId) {
        File file = new File(getModelPath(userId));
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Модель для userId=" + userId + " не знайдена. Спочатку запустіть навчання.");
        }
        try {
            model = ModelSerializer.restoreMultiLayerNetwork(file);

            java.util.Properties props = new java.util.Properties();
            try (var in = new java.io.FileInputStream(getNormPath(userId))) {
                props.load(in);
                minValue = Double.parseDouble(props.getProperty("minValue", "0.0"));
                maxValue = Double.parseDouble(props.getProperty("maxValue", "1.0"));
            }

            log.info("MLService: модель завантажена для userId={}, min={}, max={}", userId, minValue, maxValue);
        } catch (IOException e) {
            throw new RuntimeException("Не вдалося завантажити модель для userId=" + userId, e);
        }
    }
}

package com.energy.monitoring.service;

import com.energy.monitoring.entity.Anomaly;
import com.energy.monitoring.entity.AnomalyType;
import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.Reading;
import com.energy.monitoring.repository.AnomalyRepository;
import com.energy.monitoring.repository.DeviceRepository;
import com.energy.monitoring.repository.ReadingRepository;
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

    private double safeForecastValue(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, value);
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

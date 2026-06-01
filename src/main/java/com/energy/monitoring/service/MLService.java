package com.energy.monitoring.service;

import com.energy.monitoring.dto.DataQualityReport;
import com.energy.monitoring.dto.ModelStatusResponse;
import com.energy.monitoring.dto.TrainingResult;
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
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class MLService {

    private static final int WINDOW_SIZE = 144;
    private static final int FORECAST_SIZE = 144;
    private static final String SIMULATED_ANOMALY_SOURCE = "SIMULATED_ANOMALY";

    private final DeviceRepository deviceRepository;
    private final ReadingRepository readingRepository;
    private final AnomalyRepository anomalyRepository;
    private final DataQualityService dataQualityService;
    private final ConsumptionProfileService consumptionProfileService;

    private MultiLayerNetwork model;
    private double minValue = 0.0;
    private double maxValue = 1.0;

    private String getModelPath(Long userId) {
        return "src/main/resources/model/lstm_model_" + userId + ".zip";
    }

    private String getNormPath(Long userId) {
        return "src/main/resources/model/normalization_" + userId + ".properties";
    }

    private String getMetricsPath(Long userId) {
        return "src/main/resources/model/training_metrics_" + userId + ".properties";
    }

    private File getModelDirectory() {
        return new File("src/main/resources/model");
    }

    public TrainingResult train(Long userId) {
        log.info("MLService: starting training for userId={}", userId);

        DataQualityReport dataQuality = dataQualityService.ensureTrainingReady(userId, WINDOW_SIZE, FORECAST_SIZE);
        List<Double> series = loadAggregatedSeries(userId);
        if (series.size() < WINDOW_SIZE + FORECAST_SIZE) {
            throw new IllegalStateException("Not enough data for training. Need at least "
                    + (WINDOW_SIZE + FORECAST_SIZE) + " points.");
        }

        int sampleCount = series.size() - WINDOW_SIZE - FORECAST_SIZE + 1;
        if (sampleCount < 3) {
            throw new IllegalStateException("Not enough data to create train/validation/test samples.");
        }

        Split split = chronologicalSplit(sampleCount);
        int trainValueEnd = Math.min(series.size(), split.trainSamples() + WINDOW_SIZE + FORECAST_SIZE - 1);
        List<Double> trainingValues = series.subList(0, trainValueEnd);
        minValue = trainingValues.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        maxValue = trainingValues.stream().mapToDouble(Double::doubleValue).max().orElse(1);

        double[] normalized = normalize(series);
        INDArray input = Nd4j.zeros(sampleCount, 1, WINDOW_SIZE);
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
            for (int i = 0; i < split.trainSamples(); i += batchSize) {
                int end = Math.min(i + batchSize, split.trainSamples());
                model.fit(slice(input, i, end), slice(labels, i, end));
            }
            if (epoch % 2 == 0) {
                log.info("MLService: epoch {}/{}, loss={}", epoch, epochs, model.score());
            }
        }

        int validationStart = split.trainSamples();
        int testStart = split.trainSamples() + split.validationSamples();
        Metrics trainMetrics = evaluate(input, labels, 0, split.trainSamples());
        Metrics validationMetrics = evaluate(input, labels, validationStart, testStart);
        Metrics testMetrics = evaluate(input, labels, testStart, sampleCount);

        TrainingResult result = new TrainingResult(
                "ok",
                series.size(),
                sampleCount,
                split.trainSamples(),
                split.validationSamples(),
                split.testSamples(),
                trainMetrics.mse(),
                trainMetrics.mae(),
                validationMetrics.mse(),
                validationMetrics.mae(),
                testMetrics.mse(),
                testMetrics.mae(),
                minValue,
                maxValue,
                dataQuality
        );

        saveModel(userId, result);
        log.info("MLService: training completed for userId={}, trainMse={}, validationMse={}, testMse={}",
                userId, result.trainMse(), result.validationMse(), result.testMse());
        return result;
    }

    public ModelStatusResponse getModelStatus(Long userId) {
        DataQualityReport dataQuality = dataQualityService.analyzeUserSeries(userId, WINDOW_SIZE, FORECAST_SIZE);
        File modelFile = new File(getModelPath(userId));
        if (!modelFile.exists()) {
            if (hasForecastInput(userId) && findDemoModel(userId) != null) {
                log.info("MLService: assigning demo model automatically for forecast, userId={}", userId);
                return useDemoModel(userId);
            }

            return new ModelStatusResponse(
                    "NOT_TRAINED",
                    false,
                    "Модель ще не навчена. Додайте пристрої або використайте готову демо-модель перед показом прогнозу.",
                    userId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    dataQuality
            );
        }

        Properties metrics = loadMetrics(userId);
        LocalDateTime trainedAt = parseDateTime(metrics.getProperty("trainedAt"));
        if (trainedAt == null) {
            trainedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(modelFile.lastModified()), ZoneId.systemDefault());
        }

        return new ModelStatusResponse(
                "READY",
                true,
                "Модель готова до прогнозування. Прогноз побудовано на останніх 10-хвилинних показниках.",
                userId,
                trainedAt,
                modelFile.length(),
                getInteger(metrics, "totalPoints"),
                getInteger(metrics, "totalSamples"),
                getInteger(metrics, "trainSamples"),
                getInteger(metrics, "validationSamples"),
                getInteger(metrics, "testSamples"),
                getDouble(metrics, "trainMse"),
                getDouble(metrics, "trainMae"),
                getDouble(metrics, "validationMse"),
                getDouble(metrics, "validationMae"),
                getDouble(metrics, "testMse"),
                getDouble(metrics, "testMae"),
                dataQuality
        );
    }

    public ModelStatusResponse useDemoModel(Long userId) {
        File sourceModel = findDemoModel(userId);
        if (sourceModel == null) {
            throw new IllegalStateException("Готову демо-модель не знайдено. Спочатку навчіть модель хоча б для одного користувача.");
        }

        String suffix = sourceModel.getName()
                .replace("lstm_model", "")
                .replace(".zip", "");
        File sourceNorm = new File(getModelDirectory(), "normalization" + suffix + ".properties");
        if (!sourceNorm.exists()) {
            sourceNorm = new File(getModelDirectory(), "normalization.properties");
        }
        if (!sourceNorm.exists()) {
            throw new IllegalStateException("Не знайдено параметри нормалізації для демо-моделі.");
        }

        try {
            File targetModel = new File(getModelPath(userId));
            File targetNorm = new File(getNormPath(userId));
            targetModel.getParentFile().mkdirs();

            Files.copy(sourceModel.toPath(), targetModel.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourceNorm.toPath(), targetNorm.toPath(), StandardCopyOption.REPLACE_EXISTING);
            writeDemoMetrics(userId, targetModel);

            log.info("MLService: demo model '{}' assigned to userId={}", sourceModel.getName(), userId);
            return getModelStatus(userId);
        } catch (IOException e) {
            throw new IllegalStateException("Не вдалося підключити готову демо-модель.", e);
        }
    }

    public List<Double> forecast(Long userId) {
        try {
            loadModelIfNeeded(userId);

            List<Double> series = loadAggregatedSeries(userId);
            if (series.size() < WINDOW_SIZE) {
                throw new IllegalStateException("Not enough data for neural forecast.");
            }

            List<Double> window = series.subList(series.size() - WINDOW_SIZE, series.size());
            double[] normalized = normalize(window);

            INDArray input = Nd4j.zeros(1, 1, WINDOW_SIZE);
            for (int t = 0; t < WINDOW_SIZE; t++) {
                input.putScalar(new int[]{0, 0, t}, normalized[t]);
            }

            INDArray output = model.output(input);

            List<Double> result = new ArrayList<>();
            for (int t = 0; t < FORECAST_SIZE; t++) {
                result.add(safeForecastValue(denormalize(output.getDouble(0, 0, t))));
            }

            return result;
        } catch (RuntimeException e) {
            log.warn("MLService: neural forecast unavailable for userId={}, using historical fallback: {}",
                    userId, e.getMessage());
            return historicalForecast(userId);
        }
    }

    public List<Anomaly> detectAnomalies(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return List.of();
        }

        List<Anomaly> detected = new ArrayList<>();
        LocalDateTime from = LocalDateTime.now().minusDays(1);

        for (Device device : devices) {
            List<Reading> recentReadings = readingRepository
                    .findByDeviceAndTimestampAfterOrderByTimestampAsc(device, from);

            if (recentReadings.isEmpty()) {
                continue;
            }

            LocalDateTime statsFrom = LocalDateTime.now().minusDays(14);
            List<Reading> historyReadings = readingRepository
                    .findByDeviceAndTimestampAfterOrderByTimestampAsc(device, statsFrom);

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

            for (Reading reading : recentReadings) {
                double actual = reading.getPowerConsumption().doubleValue();

                if (anomalyRepository.existsByDeviceAndTimestamp(device, reading.getTimestamp())) {
                    continue;
                }

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
                        log.info("MLService: anomaly '{}' for '{}' at {} (z={})",
                                type, device.getName(), reading.getTimestamp(), String.format("%.2f", zScore));
                    }
                }
            }
        }

        log.info("MLService: detected {} anomalies", detected.size());
        return detected;
    }

    public Anomaly simulateAnomaly(Long deviceId, Long userId) {
        Device device = resolveAnomalyDevice(deviceId, userId);
        long anomalyCount = countUserAnomalies(userId);
        AnomalyType type = anomalyCount % 3 == 1 ? AnomalyType.DROP : AnomalyType.SPIKE;
        Anomaly saved = createAnomaly(device, type, LocalDateTime.now());
        log.info("MLService: simulated {} anomaly for '{}'", type, device.getName());
        return saved;
    }

    public List<Anomaly> generateScheduledAnomalies(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return List.of();
        }

        Anomaly latest = anomalyRepository.findTopByDeviceInOrderByDetectedAtDesc(devices);
        if (latest != null) {
            long hoursSinceLast = ChronoUnit.HOURS.between(latest.getDetectedAt(), LocalDateTime.now());
            if (hoursSinceLast < 3) {
                return List.of();
            }
            if (hoursSinceLast < 6 && ThreadLocalRandom.current().nextDouble() > 0.35) {
                return List.of();
            }
        }

        Device device = devices.get(ThreadLocalRandom.current().nextInt(devices.size()));
        AnomalyType type = ThreadLocalRandom.current().nextBoolean() ? AnomalyType.SPIKE : AnomalyType.DROP;
        Anomaly anomaly = createAnomaly(device, type, LocalDateTime.now());
        log.info("MLService: scheduled {} anomaly for '{}'", type, device.getName());
        return List.of(anomaly);
    }

    private List<Double> loadAggregatedSeries(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            throw new IllegalStateException("No active devices found.");
        }

        List<Double> series = readingRepository
                .findAggregatedByDevicesOrderByTimestampExcludingSource(devices, SIMULATED_ANOMALY_SOURCE)
                .stream()
                .map(Double::valueOf)
                .toList();

        int maxPoints = 14 * 144;
        if (series.size() > maxPoints) {
            series = series.subList(series.size() - maxPoints, series.size());
        }

        return series;
    }

    private boolean hasForecastInput(Long userId) {
        try {
            return loadAggregatedSeries(userId).size() >= WINDOW_SIZE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<Double> historicalForecast(Long userId) {
        return consumptionProfileService.tenMinuteForecast(userId, FORECAST_SIZE)
                .stream()
                .map(this::safeForecastValue)
                .toList();
    }

    private double estimateActiveNominalPower(Long userId) {
        return deviceRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .stream()
                .map(Device::getNominalPower)
                .filter(value -> value != null)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
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

    private Split chronologicalSplit(int sampleCount) {
        int trainSamples = Math.max(1, (int) Math.floor(sampleCount * 0.70));
        int validationSamples = Math.max(1, (int) Math.floor(sampleCount * 0.15));
        int testSamples = sampleCount - trainSamples - validationSamples;

        if (testSamples < 1) {
            testSamples = 1;
            if (validationSamples > 1) {
                validationSamples--;
            } else {
                trainSamples--;
            }
        }

        return new Split(trainSamples, validationSamples, testSamples);
    }

    private Metrics evaluate(INDArray input, INDArray labels, int from, int to) {
        if (to <= from) {
            return new Metrics(0, 0);
        }

        INDArray predictions = model.output(slice(input, from, to));
        INDArray actuals = slice(labels, from, to);
        long count = actuals.length();
        double squaredError = 0;
        double absoluteError = 0;

        for (int i = 0; i < count; i++) {
            double predicted = denormalize(predictions.getDouble(i));
            double actual = denormalize(actuals.getDouble(i));
            double error = predicted - actual;
            squaredError += error * error;
            absoluteError += Math.abs(error);
        }

        return new Metrics(squaredError / count, absoluteError / count);
    }

    private INDArray slice(INDArray array, int from, int to) {
        return array.get(
                NDArrayIndex.interval(from, to),
                NDArrayIndex.all(),
                NDArrayIndex.all()
        );
    }

    private double[] normalize(List<Double> values) {
        return values.stream()
                .mapToDouble(v -> (v - minValue) / (maxValue - minValue + 1e-8))
                .toArray();
    }

    private double denormalize(double value) {
        return value * (maxValue - minValue) + minValue;
    }

    private void saveModel(Long userId, TrainingResult result) {
        try {
            File file = new File(getModelPath(userId));
            file.getParentFile().mkdirs();
            ModelSerializer.writeModel(model, file, true);

            Properties props = new Properties();
            props.setProperty("minValue", String.valueOf(minValue));
            props.setProperty("maxValue", String.valueOf(maxValue));
            try (var out = new java.io.FileOutputStream(getNormPath(userId))) {
                props.store(out, "Normalization params");
            }

            Properties metrics = new Properties();
            metrics.setProperty("trainedAt", LocalDateTime.now().toString());
            metrics.setProperty("totalPoints", String.valueOf(result.totalPoints()));
            metrics.setProperty("totalSamples", String.valueOf(result.totalSamples()));
            metrics.setProperty("trainSamples", String.valueOf(result.trainSamples()));
            metrics.setProperty("validationSamples", String.valueOf(result.validationSamples()));
            metrics.setProperty("testSamples", String.valueOf(result.testSamples()));
            metrics.setProperty("trainMse", String.valueOf(result.trainMse()));
            metrics.setProperty("trainMae", String.valueOf(result.trainMae()));
            metrics.setProperty("validationMse", String.valueOf(result.validationMse()));
            metrics.setProperty("validationMae", String.valueOf(result.validationMae()));
            metrics.setProperty("testMse", String.valueOf(result.testMse()));
            metrics.setProperty("testMae", String.valueOf(result.testMae()));
            try (var out = new java.io.FileOutputStream(getMetricsPath(userId))) {
                metrics.store(out, "Training metrics");
            }
        } catch (IOException e) {
            log.error("MLService: failed to save model for userId={}", userId, e);
            throw new IllegalStateException("Model was trained but could not be saved.", e);
        }
    }

    private BigDecimal estimateExpectedValue(Device device) {
        LocalDateTime statsFrom = LocalDateTime.now().minusDays(14);
        List<Reading> historyReadings = readingRepository
                .findByDeviceAndTimestampAfterOrderByTimestampAsc(device, statsFrom)
                .stream()
                .filter(reading -> !SIMULATED_ANOMALY_SOURCE.equals(reading.getSource()))
                .toList();

        if (historyReadings.size() >= 10) {
            double mean = historyReadings.stream()
                    .mapToDouble(r -> r.getPowerConsumption().doubleValue())
                    .average()
                    .orElse(0);
            return BigDecimal.valueOf(mean).setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal nominal = device.getNominalPower() != null
                ? device.getNominalPower()
                : BigDecimal.valueOf(1.0);
        return nominal.setScale(4, RoundingMode.HALF_UP);
    }

    private Device resolveAnomalyDevice(Long deviceId, Long userId) {
        if (deviceId != null) {
            return deviceRepository.findByIdAndUserId(deviceId, userId)
                    .orElseThrow(() -> new RuntimeException("Device not found"));
        }

        List<Device> devices = deviceRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            throw new IllegalStateException("Add at least one active device before simulating anomalies.");
        }

        int index = (int) (anomalyRepository.countByDeviceIn(devices) % devices.size());
        return devices.get(index);
    }

    private long countUserAnomalies(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return 0;
        }

        return anomalyRepository.countByDeviceIn(devices);
    }

    private BigDecimal simulateActualValue(BigDecimal expected, AnomalyType type) {
        BigDecimal safeExpected = expected.compareTo(BigDecimal.ZERO) > 0
                ? expected
                : BigDecimal.valueOf(1.0);

        double factor = type == AnomalyType.SPIKE
                ? ThreadLocalRandom.current().nextDouble(1.8, 3.6)
                : ThreadLocalRandom.current().nextDouble(0.05, 0.35);

        return safeExpected
                .multiply(BigDecimal.valueOf(factor))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private Anomaly createAnomaly(Device device, AnomalyType type, LocalDateTime timestamp) {
        BigDecimal expected = estimateExpectedValue(device);
        BigDecimal actual = simulateActualValue(expected, type);
        BigDecimal deviationPercent = calculateDeviationPercent(actual, expected);

        Anomaly anomaly = Anomaly.builder()
                .device(device)
                .timestamp(timestamp)
                .actualValue(actual)
                .expectedValue(expected)
                .deviationPercent(deviationPercent)
                .type(type)
                .build();

        return anomalyRepository.save(anomaly);
    }

    private BigDecimal calculateDeviationPercent(BigDecimal actual, BigDecimal expected) {
        if (expected.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return actual.subtract(expected)
                .abs()
                .divide(expected, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void loadModelIfNeeded(Long userId) {
        File file = new File(getModelPath(userId));
        if (!file.exists()) {
            throw new IllegalStateException("Модель ще не навчена. Спочатку підготуйте готову модель або запустіть навчання в службовому режимі.");
        }
        try {
            model = ModelSerializer.restoreMultiLayerNetwork(file);

            Properties props = new Properties();
            try (var in = new java.io.FileInputStream(getNormPath(userId))) {
                props.load(in);
                minValue = Double.parseDouble(props.getProperty("minValue", "0.0"));
                maxValue = Double.parseDouble(props.getProperty("maxValue", "1.0"));
            }

            log.info("MLService: model loaded for userId={}, min={}, max={}", userId, minValue, maxValue);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load model for userId=" + userId, e);
        }
    }

    private Properties loadMetrics(Long userId) {
        Properties props = new Properties();
        File file = new File(getMetricsPath(userId));
        if (!file.exists()) {
            return props;
        }

        try (var in = new java.io.FileInputStream(file)) {
            props.load(in);
            return props;
        } catch (IOException e) {
            log.warn("MLService: could not load training metrics for userId={}", userId, e);
            return props;
        }
    }

    private File findDemoModel(Long userId) {
        File directory = getModelDirectory();
        File[] candidates = directory.listFiles((dir, name) ->
                name.startsWith("lstm_model") && name.endsWith(".zip") && !name.equals("lstm_model_" + userId + ".zip")
        );
        if (candidates == null || candidates.length == 0) {
            return null;
        }

        return List.of(candidates).stream()
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(null);
    }

    private void writeDemoMetrics(Long userId, File modelFile) throws IOException {
        DataQualityReport dataQuality = dataQualityService.analyzeUserSeries(userId, WINDOW_SIZE, FORECAST_SIZE);
        Properties metrics = new Properties();
        metrics.setProperty("trainedAt", LocalDateTime.ofInstant(
                Instant.ofEpochMilli(modelFile.lastModified()),
                ZoneId.systemDefault()
        ).toString());
        metrics.setProperty("totalPoints", String.valueOf(dataQuality.totalReadings()));
        metrics.setProperty("totalSamples", String.valueOf(dataQuality.availableTrainingWindows()));
        try (var out = new java.io.FileOutputStream(getMetricsPath(userId))) {
            metrics.store(out, "Demo model metrics");
        }
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Integer getInteger(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double getDouble(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Split(int trainSamples, int validationSamples, int testSamples) {
    }

    private record Metrics(double mse, double mae) {
    }
}

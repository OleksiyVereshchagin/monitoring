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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
        loadModelIfNeeded(userId);

        List<Double> series = loadAggregatedSeries(userId);
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
            result.add(denormalize(val));
        }

        return result;
    }

    // ── Розпізнання аномалій ────────────────────────────────────────────────────

    public List<Anomaly> detectAnomalies(Long userId) {
        loadModelIfNeeded(userId);

        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            return List.of();
        }

        List<Anomaly> detected = new ArrayList<>();
        LocalDateTime from = LocalDateTime.now().minusDays(1);

        for (Device device : devices) {
            // Отримуємо readings за останні 24 години
            List<Reading> recentReadings = readingRepository
                    .findByDeviceAndTimestampAfterOrderByTimestampAsc(device, from);

            if (recentReadings.isEmpty()) {
                continue;
            }

            // Середнє і стандартне відхилення за останні 14 днів
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


    public void simulateAnomaly(Long deviceId, Long userId) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new RuntimeException("Пристрій не знайдено"));

        BigDecimal nominal = device.getNominalPower() != null
                ? device.getNominalPower()
                : BigDecimal.valueOf(1.0);

        Reading anomalousReading = Reading.builder()
                .device(device)
                .timestamp(LocalDateTime.now())
                .powerConsumption(nominal.multiply(BigDecimal.valueOf(10)))
                .source("SIMULATED_ANOMALY")
                .build();

        readingRepository.save(anomalousReading);
        log.info("MLService: симульовано аномалію для '{}'", device.getName());
    }

    // ── Допоміжні методи ────────────────────────────────────────────────────

    private List<Double> loadAggregatedSeries(Long userId) {
        List<Device> devices = deviceRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        if (devices.isEmpty()) {
            throw new IllegalStateException("Пристроїв не знайдено.");
        }

        List<Double> series = readingRepository
                .findAggregatedByDevicesOrderByTimestamp(devices)
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
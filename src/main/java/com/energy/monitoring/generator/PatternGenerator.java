package com.energy.monitoring.generator;

import com.energy.monitoring.entity.Device;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Random;

@Component
public class PatternGenerator {

    private final Random random = new Random();

    public BigDecimal generate(Device device, LocalDateTime time) {
        int hour = time.getHour();
        double base = getBaseConsumption(device, hour);
        double noise = 1.0 + (random.nextDouble() - 0.5) * 0.15; // ±7.5% шум
        double anomaly = applyAnomaly();
        double result = base * noise * anomaly;

        return BigDecimal.valueOf(Math.max(0.01, result))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private double getBaseConsumption(Device device, int hour) {
        // Якщо є номінальна потужність — використовуємо її як базу
        double nominal = device.getNominalPower() != null
                ? device.getNominalPower().doubleValue()
                : getFallbackNominal(device.getType().name());

        double factor = getHourlyFactor(device.getType().name(), hour);
        return nominal * factor;
    }

    // Коефіцієнт споживання залежно від типу пристрою та години
    private double getHourlyFactor(String type, int hour) {
        return switch (type) {
            case "FRIDGE" ->
                // Холодильник: постійне споживання, трохи більше вдень
                    (hour >= 10 && hour <= 20) ? 1.0 : 0.7;

            case "AC" ->
                // Кондиціонер: пік вдень і ввечері
                    (hour >= 12 && hour <= 21) ? 1.0 :
                            (hour >= 22 || hour <= 6) ? 0.0 : 0.3;

            case "LIGHT" ->
                // Освітлення: ранок і вечір
                    (hour >= 7 && hour <= 9) ? 0.8 :
                            (hour >= 18 && hour <= 23) ? 1.0 :
                                    (hour >= 0 && hour <= 6) ? 0.05 : 0.2;

            case "HEATER" ->
                // Обігрівач: ранок і вечір
                    (hour >= 6 && hour <= 9) ? 1.0 :
                            (hour >= 17 && hour <= 22) ? 0.9 :
                                    (hour >= 23 || hour <= 5) ? 0.3 : 0.1;

            case "WASHING_MACHINE" ->
                // Пральна машина: активна вранці та ввечері
                    (hour >= 8 && hour <= 10) ? 1.0 :
                            (hour >= 19 && hour <= 21) ? 0.8 : 0.0;

            case "BOILER" ->
                // Бойлер: ранковий та вечірній пік
                    (hour >= 6 && hour <= 8) ? 1.0 :
                            (hour >= 19 && hour <= 21) ? 0.7 : 0.15;

            case "TV" ->
                // Телевізор: вечір
                    (hour >= 18 && hour <= 23) ? 1.0 :
                            (hour >= 10 && hour <= 17) ? 0.3 : 0.0;

            case "OVEN" ->
                // Духовка: обід і вечеря
                    (hour >= 12 && hour <= 13) ? 1.0 :
                            (hour >= 18 && hour <= 20) ? 0.9 : 0.0;

            case "ROUTER" ->
                // Роутер: постійно
                    1.0;

            case "EV_CHARGER" ->
                // Зарядка авто: ніч
                    (hour >= 22 || hour <= 6) ? 1.0 : 0.0;

            default -> 0.5;
        };
    }

    // Запасна номінальна потужність якщо не вказана в БД
    private double getFallbackNominal(String type) {
        return switch (type) {
            case "FRIDGE" -> 0.15;
            case "AC" -> 1.5;
            case "LIGHT" -> 0.06;
            case "HEATER" -> 2.0;
            case "WASHING_MACHINE" -> 2.0;
            case "BOILER" -> 2.0;
            case "TV" -> 0.1;
            case "OVEN" -> 2.5;
            case "ROUTER" -> 0.01;
            case "EV_CHARGER" -> 7.4;
            default -> 0.5;
        };
    }

    // Аномалія: ~5% шанс стрибка або падіння
    private double applyAnomaly() {
        int roll = random.nextInt(100);
        if (roll < 3) return 2.5 + random.nextDouble(); // стрибок
        if (roll < 5) return 0.05 + random.nextDouble() * 0.1; // падіння
        return 1.0;
    }
}
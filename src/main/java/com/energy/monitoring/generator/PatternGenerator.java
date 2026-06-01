package com.energy.monitoring.generator;

import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.DeviceType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Component
public class PatternGenerator {

    public BigDecimal generate(Device device, LocalDateTime time) {
        double hour = time.getHour() + time.getMinute() / 60.0;
        double result = getBaseConsumption(device, hour) * deterministicNoise(device, time);

        return BigDecimal.valueOf(Math.max(0.01, result))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private double getBaseConsumption(Device device, double hour) {
        double nominal = device.getNominalPower() != null
                ? device.getNominalPower().doubleValue()
                : getFallbackNominal(device.getType());

        return nominal * getHourlyFactor(device.getType(), hour);
    }

    private double getHourlyFactor(DeviceType type, double hour) {
        double morning = peak(hour, 8.0, 1.25);
        double evening = peak(hour, 20.0, 1.8);
        double midday = peak(hour, 13.0, 2.8);
        double night = peak(hour, 2.0, 2.5);

        return switch (type) {
            case FRIDGE, REFRIGERATOR, FREEZER -> 0.42 + 0.06 * midday + 0.04 * night;
            case ROUTER, SMART_PLUG, CHARGER -> 0.95;
            case LIGHT, LIGHTING -> 0.06 + 0.28 * morning + 0.92 * evening;
            case TV, COMPUTER, LAPTOP -> 0.04 + 0.14 * midday + 0.78 * evening;
            case AC -> 0.08 + 0.38 * midday + 0.5 * evening;
            case HEATER -> 0.12 + 0.55 * morning + 0.62 * evening + 0.14 * night;
            case BOILER -> 0.12 + 0.85 * morning + 0.58 * evening;
            case WASHING_MACHINE, DRYER, DISHWASHER -> 0.03 + 0.38 * morning + 0.65 * evening;
            case OVEN, STOVE, MICROWAVE, KETTLE, COFFEE_MACHINE -> 0.025 + 0.3 * morning + 0.72 * evening;
            case VACUUM -> 0.015 + 0.16 * midday + 0.08 * evening;
            case WATER_PUMP -> 0.16 + 0.24 * morning + 0.28 * evening;
            case EV_CHARGER -> 0.02 + 0.52 * night;
            case SOLAR_INVERTER, BATTERY_STORAGE -> 0.08 + 0.2 * midday;
            case OTHER -> 0.1 + 0.24 * morning + 0.38 * evening;
        };
    }

    private double getFallbackNominal(DeviceType type) {
        return switch (type) {
            case FRIDGE, REFRIGERATOR, FREEZER -> 0.18;
            case TV -> 0.12;
            case AC -> 1.4;
            case HEATER -> 1.7;
            case BOILER -> 1.8;
            case WASHING_MACHINE, DRYER, DISHWASHER -> 1.4;
            case OVEN, STOVE -> 2.0;
            case MICROWAVE, KETTLE, COFFEE_MACHINE -> 1.2;
            case LIGHT, LIGHTING -> 0.08;
            case COMPUTER, LAPTOP -> 0.18;
            case ROUTER, CHARGER, SMART_PLUG -> 0.03;
            case VACUUM -> 1.0;
            case WATER_PUMP -> 0.5;
            case EV_CHARGER -> 3.5;
            case SOLAR_INVERTER, BATTERY_STORAGE -> 0.4;
            case OTHER -> 0.4;
        };
    }

    private double peak(double hour, double center, double width) {
        double direct = Math.abs(hour - center);
        double circularDistance = Math.min(direct, 24.0 - direct);
        return Math.exp(-Math.pow(circularDistance, 2.0) / (2.0 * Math.pow(width, 2.0)));
    }

    private double deterministicNoise(Device device, LocalDateTime time) {
        long devicePart = device.getId() != null ? device.getId() : 0L;
        long slot = time.getDayOfYear() * 144L + time.getHour() * 6L + time.getMinute() / 10L;
        long hash = Math.abs(devicePart * 31L + slot * 17L);
        return 0.97 + (hash % 7) * 0.01;
    }
}

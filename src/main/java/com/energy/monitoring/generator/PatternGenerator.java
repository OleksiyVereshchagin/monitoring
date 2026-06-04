package com.energy.monitoring.generator;

import com.energy.monitoring.entity.BehaviorProfile;
import com.energy.monitoring.entity.Device;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDateTime;

@Component
public class PatternGenerator {

    public BigDecimal generate(Device device, LocalDateTime time) {
        double hour = time.getHour() + time.getMinute() / 60.0;
        DayType dayType = resolveDayType(time);
        double nominal = nominalPower(device);
        double result = nominal
                * deviceTypeFactor(device.getType().name(), hour, dayType)
                * behaviorFactor(device.getBehaviorProfile(), hour, dayType)
                * deterministicNoise(device, time);

        return BigDecimal.valueOf(Math.max(0.01, result))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private double deviceTypeFactor(String type, double hour, DayType dayType) {
        double morning = peak(hour, dayType == DayType.WEEKEND ? 9.3 : 8.0, 1.45);
        double afternoon = peak(hour, 16.6, dayType == DayType.WORKDAY ? 2.2 : 3.2);
        double evening = peak(hour, 20.0, 2.15);
        double night = peak(hour, 2.0, 2.8);
        double dayPresence = dayPresence(dayType, hour);

        return switch (type) {
            case "FRIDGE", "REFRIGERATOR", "FREEZER" -> 0.62 + 0.08 * afternoon + 0.04 * night;
            case "ROUTER", "SMART_PLUG", "CHARGER" -> 0.9;
            case "LIGHT", "LIGHTING" -> 0.06 + 0.28 * morning + 0.9 * evening + 0.12 * dayPresence;
            case "TV", "COMPUTER", "LAPTOP" -> 0.04 + 0.16 * dayPresence + 0.7 * evening;
            case "AC" -> 0.08 + 0.32 * afternoon + 0.42 * evening + 0.18 * dayPresence;
            case "HEATER" -> 0.12 + 0.44 * morning + 0.5 * evening + 0.12 * night;
            case "BOILER" -> 0.14 + 0.72 * morning + 0.5 * evening + 0.18 * afternoon;
            case "WASHING_MACHINE", "DRYER", "DISHWASHER" -> 0.035 + 0.28 * morning + 0.38 * afternoon + 0.5 * evening;
            case "OVEN", "STOVE", "MICROWAVE", "KETTLE", "COFFEE_MACHINE" ->
                    0.03 + 0.28 * morning + 0.28 * afternoon + 0.62 * evening;
            case "VACUUM" -> 0.02 + 0.22 * dayPresence + 0.08 * evening;
            case "WATER_PUMP" -> 0.16 + 0.18 * morning + 0.2 * afternoon + 0.22 * evening;
            case "EV_CHARGER" -> 0.02 + 0.48 * night;
            case "SOLAR_INVERTER", "BATTERY_STORAGE" -> 0.07 + 0.22 * peak(hour, 13.0, 3.6);
            default -> 0.1 + 0.24 * morning + 0.18 * afternoon + 0.34 * evening;
        };
    }

    private double behaviorFactor(BehaviorProfile profile, double hour, DayType dayType) {
        BehaviorProfile effectiveProfile = profile != null ? profile : BehaviorProfile.INTERMITTENT;
        double presence = dayPresence(dayType, hour);
        double morning = peak(hour, dayType == DayType.WEEKEND ? 9.3 : 8.0, 1.6);
        double afternoon = peak(hour, 16.6, dayType == DayType.WORKDAY ? 2.4 : 3.4);
        double evening = peak(hour, 20.0, 2.1);
        double active = Math.max(Math.max(morning, afternoon), evening);

        return switch (effectiveProfile) {
            case CONSTANT -> 0.95;
            case CYCLIC -> 0.82 + 0.1 * Math.sin((hour - 6.0) / 24.0 * 2.0 * Math.PI) + 0.08 * presence;
            case INTERMITTENT -> 0.34 + 0.48 * presence + 0.18 * active;
            case PEAK_BASED -> 0.18 + 0.78 * active + 0.14 * presence;
        };
    }

    private double dayPresence(DayType dayType, double hour) {
        double wakeHours = peak(hour, 12.8, 5.2);
        double afterWork = peak(hour, 17.2, 2.4);
        double evening = peak(hour, 20.0, 2.4);

        return switch (dayType) {
            case WORKDAY -> 0.18 + 0.22 * wakeHours + 0.34 * afterWork + 0.34 * evening;
            case HOME_DAY -> 0.42 + 0.42 * wakeHours + 0.18 * evening;
            case WEEKEND -> 0.48 + 0.42 * peak(hour, 13.6, 5.0) + 0.16 * evening;
        };
    }

    private DayType resolveDayType(LocalDateTime time) {
        DayOfWeek day = time.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return DayType.WEEKEND;
        }

        long marker = Math.abs(time.toLocalDate().toEpochDay() + 13) % 6;
        return marker == 0 ? DayType.HOME_DAY : DayType.WORKDAY;
    }

    private double nominalPower(Device device) {
        if (device.getNominalPower() != null && device.getNominalPower().compareTo(BigDecimal.ZERO) > 0) {
            return device.getNominalPower().doubleValue();
        }
        return getFallbackNominal(device.getType().name());
    }

    private double getFallbackNominal(String type) {
        return switch (type) {
            case "FRIDGE", "REFRIGERATOR", "FREEZER" -> 0.18;
            case "AC" -> 1.4;
            case "LIGHT", "LIGHTING" -> 0.08;
            case "HEATER" -> 1.7;
            case "WASHING_MACHINE", "DRYER", "DISHWASHER" -> 1.4;
            case "BOILER" -> 1.8;
            case "TV" -> 0.12;
            case "OVEN", "STOVE" -> 2.0;
            case "MICROWAVE", "KETTLE", "COFFEE_MACHINE" -> 1.2;
            case "ROUTER", "SMART_PLUG", "CHARGER" -> 0.03;
            case "COMPUTER", "LAPTOP" -> 0.18;
            case "EV_CHARGER" -> 3.5;
            case "VACUUM" -> 1.0;
            case "WATER_PUMP" -> 0.5;
            case "SOLAR_INVERTER", "BATTERY_STORAGE" -> 0.4;
            default -> 0.4;
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

    private enum DayType {
        WORKDAY,
        HOME_DAY,
        WEEKEND
    }
}

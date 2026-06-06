package com.energy.monitoring.generator;

import com.energy.monitoring.entity.BehaviorProfile;
import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.HouseholdType;
import com.energy.monitoring.entity.PresenceMode;
import com.energy.monitoring.entity.SimulationProfile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Component
public class PatternGenerator {

    public BigDecimal generate(Device device, LocalDateTime time) {
        return generate(device, time, null);
    }

    public BigDecimal generate(Device device, LocalDateTime time, SimulationProfile profile) {
        double hour = time.getHour() + time.getMinute() / 60.0;
        DayType dayType = resolveDayType(time, profile);
        double nominal = nominalPower(device);
        Double eventPower = eventPower(device, time, profile, nominal);
        if (eventPower != null) {
            return BigDecimal.valueOf(Math.max(0.0, eventPower))
                    .setScale(4, RoundingMode.HALF_UP);
        }

        double result = nominal
                * deviceTypeFactor(device.getType().name(), hour, dayType)
                * behaviorFactor(device.getBehaviorProfile(), hour, dayType)
                * simulationFactor(device.getType().name(), hour, dayType, profile, time)
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
            case "TV", "COMPUTER", "DESKTOP_PC", "LAPTOP", "MONITOR", "GAME_CONSOLE", "SPEAKERS" ->
                    0.04 + 0.16 * dayPresence + 0.7 * evening;
            case "AC", "FAN" -> 0.08 + 0.32 * afternoon + 0.42 * evening + 0.18 * dayPresence;
            case "HEATER" -> 0.12 + 0.44 * morning + 0.5 * evening + 0.12 * night;
            case "BOILER" -> 0.14 + 0.72 * morning + 0.5 * evening + 0.18 * afternoon;
            case "GAS_BOILER" -> 0.08 + 0.28 * morning + 0.34 * evening + 0.16 * night;
            case "WASHING_MACHINE", "DRYER", "DISHWASHER" -> 0.035 + 0.28 * morning + 0.38 * afternoon + 0.5 * evening;
            case "OVEN", "STOVE", "MICROWAVE", "KETTLE", "COFFEE_MACHINE", "TOASTER", "IRON", "HAIR_DRYER" ->
                    0.03 + 0.28 * morning + 0.28 * afternoon + 0.62 * evening;
            case "AIR_PURIFIER" -> 0.42 + 0.12 * dayPresence + 0.08 * night;
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

    private DayType resolveDayType(LocalDateTime time, SimulationProfile profile) {
        DayOfWeek day = time.getDayOfWeek();
        String weekendDays = profile != null && profile.getWeekendDays() != null
                ? profile.getWeekendDays()
                : "SATURDAY,SUNDAY";

        if (!"NONE".equalsIgnoreCase(weekendDays) && weekendDays.contains(day.name())) {
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
            case "GAS_BOILER" -> 0.12;
            case "TV" -> 0.12;
            case "OVEN", "STOVE" -> 2.0;
            case "MICROWAVE", "KETTLE", "COFFEE_MACHINE" -> 1.2;
            case "TOASTER" -> 0.9;
            case "IRON", "HAIR_DRYER" -> 1.6;
            case "ROUTER", "SMART_PLUG", "CHARGER" -> 0.03;
            case "COMPUTER", "LAPTOP" -> 0.18;
            case "DESKTOP_PC" -> 0.3;
            case "MONITOR", "FAN", "AIR_PURIFIER" -> 0.06;
            case "GAME_CONSOLE" -> 0.16;
            case "SPEAKERS" -> 0.03;
            case "EV_CHARGER" -> 3.5;
            case "VACUUM" -> 1.0;
            case "WATER_PUMP" -> 0.5;
            case "SOLAR_INVERTER", "BATTERY_STORAGE" -> 0.4;
            default -> 0.4;
        };
    }

    private Double eventPower(Device device, LocalDateTime time, SimulationProfile profile, double nominal) {
        String type = device.getType().name();
        if (!isEventBased(type)) {
            return null;
        }

        int dayIndex = time.getDayOfWeek().getValue() - 1;
        int minuteOfWeek = dayIndex * 1440 + time.getHour() * 60 + time.getMinute();
        LocalDate weekStart = time.toLocalDate().minusDays(dayIndex);
        long weekKey = weekStart.toEpochDay() / 7L;
        int eventCount = weeklyEventCount(type, device, profile, weekKey);
        double standby = standbyPower(type);

        for (int eventIndex = 0; eventIndex < eventCount; eventIndex++) {
            long seed = eventSeed(device, type, weekKey, eventIndex);
            int eventDay = (int) (seed % 7);
            LocalDateTime eventDateTime = weekStart.plusDays(eventDay).atStartOfDay();
            DayType eventDayType = resolveDayType(eventDateTime, profile);
            int startMinute = preferredStartMinute(type, eventDayType, profile, seed);
            int duration = eventDuration(type, seed);
            int eventStart = eventDay * 1440 + startMinute;
            int delta = minuteOfWeek - eventStart;

            if (delta >= 0 && delta < duration) {
                double shape = eventShape(type, delta, duration);
                double profileFactor = eventProfileFactor(type, profile, eventDayType);
                return nominal * shape * profileFactor * deterministicNoise(device, time);
            }
        }

        return standby;
    }

    private boolean isEventBased(String type) {
        return switch (type) {
            case "OVEN", "STOVE", "MICROWAVE", "KETTLE", "COFFEE_MACHINE", "TOASTER",
                 "IRON", "HAIR_DRYER", "WASHING_MACHINE", "DRYER", "DISHWASHER", "VACUUM" -> true;
            default -> false;
        };
    }

    private int weeklyEventCount(String type, Device device, SimulationProfile profile, long weekKey) {
        int occupants = profile != null && profile.getOccupants() != null ? profile.getOccupants() : 2;
        double activity = activityFactor(profile);
        HouseholdType objectType = objectType(profile);
        long seed = eventSeed(device, type, weekKey, 97);
        int variation = (int) (seed % 3) - 1;

        int base = switch (type) {
            case "OVEN" -> 2;
            case "STOVE" -> 3;
            case "WASHING_MACHINE" -> 1 + (int) Math.round(occupants * 0.7);
            case "DRYER" -> occupants >= 3 ? 2 : 1;
            case "DISHWASHER" -> occupants >= 3 ? 4 : 2;
            case "MICROWAVE" -> 3 + occupants * 2;
            case "KETTLE" -> 4 + occupants * 3;
            case "COFFEE_MACHINE" -> 3 + occupants * 2;
            case "TOASTER" -> 2 + occupants;
            case "IRON" -> 1;
            case "HAIR_DRYER" -> occupants + 1;
            case "VACUUM" -> occupants >= 3 ? 2 : 1;
            default -> 2;
        };

        if (objectType == HouseholdType.OFFICE) {
            base = switch (type) {
                case "KETTLE", "COFFEE_MACHINE" -> 10 + occupants * 2;
                case "MICROWAVE" -> 4 + occupants;
                case "TOASTER" -> 2;
                case "VACUUM" -> 1;
                case "DISHWASHER" -> occupants >= 6 ? 2 : 1;
                case "OVEN", "STOVE", "WASHING_MACHINE", "DRYER", "IRON", "HAIR_DRYER" -> 0;
                default -> base;
            };
        } else if (objectType == HouseholdType.COTTAGE) {
            double visitFactor = switch (profile != null && profile.getPresenceMode() != null ? profile.getPresenceMode() : PresenceMode.PARTLY_HOME) {
                case OFTEN_HOME -> 0.75;
                case PARTLY_HOME -> 0.38;
                case CUSTOM -> 0.45;
                case STANDARD_WORKDAY -> 0.25;
            };
            base = (int) Math.round(base * visitFactor);
        }

        int count = (int) Math.round(base * activity) + variation;
        if (("OVEN".equals(type) || "STOVE".equals(type)) && seed % 11 == 0) {
            count = Math.max(0, count - 2);
        }
        if (("OVEN".equals(type) || "WASHING_MACHINE".equals(type)) && seed % 13 == 0) {
            count += 2;
        }

        return switch (type) {
            case "OVEN" -> clamp(count, 0, 5);
            case "STOVE" -> clamp(count, 0, 7);
            case "WASHING_MACHINE" -> clamp(count, 0, 7);
            case "DRYER" -> clamp(count, 0, 5);
            case "DISHWASHER" -> clamp(count, 0, 8);
            case "MICROWAVE" -> clamp(count, 1, 18);
            case "KETTLE", "COFFEE_MACHINE" -> clamp(count, 1, 28);
            case "TOASTER" -> clamp(count, 0, 12);
            case "IRON" -> clamp(count, 0, 3);
            case "HAIR_DRYER" -> clamp(count, 0, 18);
            case "VACUUM" -> clamp(count, 0, 3);
            default -> clamp(count, 0, 10);
        };
    }

    private int preferredStartMinute(String type, DayType dayType, SimulationProfile profile, long seed) {
        if (profile != null && profile.getPresenceMode() == PresenceMode.CUSTOM && isHeavyEvent(type)) {
            Integer customStart = customHeavyStartMinute(profile, seed);
            if (customStart != null) {
                return customStart;
            }
        }

        int start;
        int span;
        boolean weekendLike = dayType == DayType.WEEKEND || dayType == DayType.HOME_DAY;

        switch (type) {
            case "KETTLE", "COFFEE_MACHINE", "TOASTER" -> {
                start = (seed % 4 == 0 && weekendLike) ? 10 * 60 : 7 * 60;
                span = 150;
            }
            case "HAIR_DRYER" -> {
                start = seed % 3 == 0 ? 18 * 60 : 7 * 60;
                span = 120;
            }
            case "MICROWAVE" -> {
                start = seed % 2 == 0 ? 12 * 60 : 18 * 60;
                span = 180;
            }
            case "OVEN", "STOVE" -> {
                start = weekendLike && seed % 3 == 0 ? 12 * 60 : 18 * 60;
                span = 210;
            }
            case "WASHING_MACHINE", "DRYER", "DISHWASHER", "VACUUM", "IRON" -> {
                start = weekendLike ? 10 * 60 : 18 * 60;
                span = weekendLike ? 360 : 210;
            }
            default -> {
                start = 18 * 60;
                span = 180;
            }
        }

        int offset = (int) ((seed / 7) % Math.max(1, span / 10)) * 10;
        return clampToDay(start + offset);
    }

    private int eventDuration(String type, long seed) {
        int variant = (int) (seed % 5);
        return switch (type) {
            case "KETTLE", "COFFEE_MACHINE", "TOASTER", "HAIR_DRYER" -> 10;
            case "MICROWAVE" -> 10 + (variant % 2) * 10;
            case "IRON" -> 30 + variant * 10;
            case "VACUUM" -> 30 + variant * 10;
            case "STOVE" -> 30 + variant * 10;
            case "OVEN" -> 60 + variant * 15;
            case "WASHING_MACHINE" -> 80 + variant * 10;
            case "DRYER" -> 60 + variant * 10;
            case "DISHWASHER" -> 90 + variant * 10;
            default -> 60;
        };
    }

    private double eventShape(String type, int delta, int duration) {
        double progress = duration <= 0 ? 1.0 : (double) delta / duration;
        return switch (type) {
            case "KETTLE", "MICROWAVE", "COFFEE_MACHINE", "TOASTER", "HAIR_DRYER" -> 0.92;
            case "IRON" -> 0.76 + 0.08 * Math.sin(progress * Math.PI * 5.0);
            case "OVEN" -> progress < 0.25 ? 0.96 : 0.58 + 0.12 * Math.sin(progress * Math.PI * 4.0);
            case "STOVE" -> 0.42 + 0.38 * Math.sin(Math.min(1.0, progress) * Math.PI);
            case "WASHING_MACHINE" -> {
                if (progress < 0.22) yield 0.82;
                if (progress < 0.72) yield 0.18;
                yield 0.42;
            }
            case "DRYER" -> 0.62 + 0.12 * Math.sin(progress * Math.PI * 6.0);
            case "DISHWASHER" -> {
                if (progress < 0.25) yield 0.72;
                if (progress < 0.78) yield 0.24;
                yield 0.54;
            }
            case "VACUUM" -> 0.72;
            default -> 0.5;
        };
    }

    private double eventProfileFactor(String type, SimulationProfile profile, DayType dayType) {
        double activity = activityFactor(profile);
        double presence = dayType == DayType.WORKDAY ? 0.86 : 1.0;
        int occupants = profile != null && profile.getOccupants() != null ? profile.getOccupants() : 2;
        double people = switch (type) {
            case "WASHING_MACHINE", "DRYER", "DISHWASHER" -> 0.72 + Math.min(occupants, 6) * 0.12;
            case "OVEN", "STOVE", "MICROWAVE", "KETTLE", "COFFEE_MACHINE", "TOASTER", "IRON", "HAIR_DRYER" ->
                    0.84 + Math.min(occupants, 6) * 0.06;
            default -> 1.0;
        };
        return activity * presence * people;
    }

    private boolean isHeavyEvent(String type) {
        return switch (type) {
            case "OVEN", "STOVE", "WASHING_MACHINE", "DRYER", "DISHWASHER", "VACUUM", "IRON" -> true;
            default -> false;
        };
    }

    private double standbyPower(String type) {
        return switch (type) {
            case "MICROWAVE", "COFFEE_MACHINE", "DISHWASHER", "WASHING_MACHINE", "GAME_CONSOLE" -> 0.002;
            default -> 0.0;
        };
    }

    private long eventSeed(Device device, String type, long weekKey, int salt) {
        long devicePart = device.getId() != null ? device.getId() : 0L;
        long hash = 1125899906842597L;
        hash = 31L * hash + devicePart;
        hash = 31L * hash + type.hashCode();
        hash = 31L * hash + weekKey;
        hash = 31L * hash + salt;
        return hash == Long.MIN_VALUE ? 0L : Math.abs(hash);
    }

    private int minuteOfDay(LocalTime time, int fallback) {
        if (time == null) {
            return fallback;
        }
        return time.getHour() * 60 + time.getMinute();
    }

    private int clampToDay(int minute) {
        return clamp(minute, 0, 23 * 60 + 50);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double simulationFactor(String type, double hour, DayType dayType, SimulationProfile profile, LocalDateTime time) {
        int occupants = profile != null && profile.getOccupants() != null ? profile.getOccupants() : 2;
        double area = profile != null && profile.getAreaM2() != null ? profile.getAreaM2().doubleValue() : 55.0;
        double activity = activityFactor(profile);
        double presence = presenceFactor(profile, hour, dayType);
        double season = seasonFactor(type, time, profile);
        double objectLoad = objectLoadFactor(profile, hour, dayType);
        double people = 0.72 + Math.min(occupants, 6) * 0.14;
        double areaFactor = 0.82 + Math.min(area, 140.0) / 260.0;

        return switch (type) {
            case "FRIDGE", "REFRIGERATOR", "FREEZER", "ROUTER", "SMART_PLUG" ->
                    (0.96 + 0.02 * Math.min(occupants, 4)) * objectLoad;
            case "LIGHT", "LIGHTING" ->
                    activity * objectLoad * (0.56 + 0.24 * people + 0.28 * areaFactor) * (0.72 + 0.4 * presence) * season;
            case "BOILER" ->
                    activity * objectLoad * (0.58 + 0.34 * people + 0.02 * areaFactor) * season;
            case "WATER_PUMP" ->
                    activity * objectLoad * (0.58 + 0.34 * people + 0.12 * areaFactor) * season;
            case "GAS_BOILER" ->
                    activity * objectLoad * (0.72 + 0.12 * people + 0.08 * areaFactor) * season;
            case "AC", "HEATER" ->
                    activity * objectLoad * (0.68 + 0.18 * people + 0.36 * areaFactor) * (0.7 + 0.42 * presence) * season;
            case "TV", "COMPUTER", "DESKTOP_PC", "LAPTOP", "MONITOR", "GAME_CONSOLE", "SPEAKERS" ->
                    activity * objectLoad * (0.74 + 0.18 * people) * (0.52 + 0.64 * presence);
            case "WASHING_MACHINE", "DRYER", "DISHWASHER" ->
                    activity * objectLoad * (0.42 + 0.31 * people) * (0.38 + 0.82 * presence);
            case "OVEN", "STOVE", "MICROWAVE", "KETTLE", "COFFEE_MACHINE", "TOASTER",
                 "IRON", "HAIR_DRYER", "VACUUM" ->
                    activity * objectLoad * (0.62 + 0.2 * people) * (0.28 + 0.92 * presence);
            default ->
                    activity * objectLoad * (0.76 + 0.14 * people) * (0.62 + 0.46 * presence);
        };
    }

    private double activityFactor(SimulationProfile profile) {
        if (profile == null || profile.getActivityLevel() == null) {
            return 1.0;
        }

        return switch (profile.getActivityLevel()) {
            case ECONOMY -> 0.82;
            case NORMAL -> 1.0;
            case ACTIVE -> 1.18;
        };
    }

    private double presenceFactor(SimulationProfile profile, double hour, DayType dayType) {
        if (profile == null || profile.getPresenceMode() == null) {
            return dayPresence(dayType, hour);
        }

        HouseholdType objectType = objectType(profile);
        if (objectType == HouseholdType.OFFICE) {
            return officePresence(profile, hour, dayType);
        }
        if (objectType == HouseholdType.COTTAGE) {
            return cottagePresence(profile, hour, dayType);
        }

        return switch (profile.getPresenceMode()) {
            case OFTEN_HOME -> 0.72 + 0.28 * dayPresence(DayType.HOME_DAY, hour);
            case PARTLY_HOME -> 0.42 + 0.58 * dayPresence(dayType, hour);
            case CUSTOM, STANDARD_WORKDAY -> scheduledPresence(profile, hour, dayType);
        };
    }

    private HouseholdType objectType(SimulationProfile profile) {
        return profile != null && profile.getHousehold() != null ? profile.getHousehold().getType() : null;
    }

    private double objectLoadFactor(SimulationProfile profile, double hour, DayType dayType) {
        HouseholdType objectType = objectType(profile);
        if (objectType == HouseholdType.OFFICE) {
            return 0.62 + 0.5 * officePresence(profile, hour, dayType);
        }
        if (objectType == HouseholdType.COTTAGE) {
            return 0.24 + 0.86 * cottagePresence(profile, hour, dayType);
        }
        return 1.0;
    }

    private double officePresence(SimulationProfile profile, double hour, DayType dayType) {
        boolean activeDay = dayType == DayType.WEEKEND;
        if (!activeDay) {
            return 0.08;
        }
        if (isInRange(hour, profile.getAwayStart(), profile.getAwayEnd())) {
            return 0.88;
        }
        return 0.14;
    }

    private double cottagePresence(SimulationProfile profile, double hour, DayType dayType) {
        boolean visitDay = dayType == DayType.WEEKEND;
        if (profile.getPresenceMode() == PresenceMode.CUSTOM) {
            Double customPresence = customHomePresence(profile, hour);
            return customPresence != null ? customPresence : 0.08;
        }
        if (!visitDay) {
            return profile.getPresenceMode() == PresenceMode.OFTEN_HOME ? 0.22 : 0.06;
        }
        if (isInRange(hour, profile.getAwayStart(), profile.getAwayEnd())) {
            return profile.getPresenceMode() == PresenceMode.OFTEN_HOME ? 0.84 : 0.62;
        }
        return 0.1;
    }

    private double scheduledPresence(SimulationProfile profile, double hour, DayType dayType) {
        if (isInRange(hour, profile.getSleepStart(), profile.getSleepEnd())) {
            return 0.12;
        }
        if (profile.getPresenceMode() == PresenceMode.CUSTOM) {
            Double customPresence = customHomePresence(profile, hour);
            if (customPresence != null) {
                return customPresence;
            }
            if (dayType == DayType.WEEKEND) {
                return 0.66 + 0.34 * dayPresence(dayType, hour);
            }
            return 0.18;
        }
        if (dayType == DayType.WEEKEND) {
            return 0.66 + 0.34 * dayPresence(dayType, hour);
        }
        if (isInRange(hour, profile.getAwayStart(), profile.getAwayEnd())) {
            return 0.18;
        }
        return 0.86;
    }

    private Double customHomePresence(SimulationProfile profile, double hour) {
        for (int index = 1; index <= 5; index++) {
            if (isInRange(hour, customHomeStart(profile, index), customHomeEnd(profile, index))) {
                return customActivityPresence(customHomeActivity(profile, index));
            }
        }
        return null;
    }

    private double customActivityPresence(com.energy.monitoring.entity.ActivityLevel activityLevel) {
        if (activityLevel == null) {
            return 0.78;
        }

        return switch (activityLevel) {
            case ECONOMY -> 0.45;
            case NORMAL -> 0.78;
            case ACTIVE -> 0.95;
        };
    }

    private Integer customHeavyStartMinute(SimulationProfile profile, long seed) {
        int enabledCount = 0;
        for (int index = 1; index <= 5; index++) {
            if (isCustomHeavyAllowed(profile, index) && customHomeStart(profile, index) != null && customHomeEnd(profile, index) != null) {
                enabledCount++;
            }
        }
        if (enabledCount == 0) {
            return null;
        }

        int selected = (int) (seed % enabledCount);
        for (int index = 1; index <= 5; index++) {
            if (isCustomHeavyAllowed(profile, index) && customHomeStart(profile, index) != null && customHomeEnd(profile, index) != null) {
                if (selected == 0) {
                    return startInsideRange(customHomeStart(profile, index), customHomeEnd(profile, index), seed);
                }
                selected--;
            }
        }

        return null;
    }

    private LocalTime customHomeStart(SimulationProfile profile, int index) {
        return switch (index) {
            case 1 -> profile.getCustomHomeStart1();
            case 2 -> profile.getCustomHomeStart2();
            case 3 -> profile.getCustomHomeStart3();
            case 4 -> profile.getCustomHomeStart4();
            case 5 -> profile.getCustomHomeStart5();
            default -> null;
        };
    }

    private LocalTime customHomeEnd(SimulationProfile profile, int index) {
        return switch (index) {
            case 1 -> profile.getCustomHomeEnd1();
            case 2 -> profile.getCustomHomeEnd2();
            case 3 -> profile.getCustomHomeEnd3();
            case 4 -> profile.getCustomHomeEnd4();
            case 5 -> profile.getCustomHomeEnd5();
            default -> null;
        };
    }

    private com.energy.monitoring.entity.ActivityLevel customHomeActivity(SimulationProfile profile, int index) {
        return switch (index) {
            case 1 -> profile.getCustomHomeActivity1();
            case 2 -> profile.getCustomHomeActivity2();
            case 3 -> profile.getCustomHomeActivity3();
            case 4 -> profile.getCustomHomeActivity4();
            case 5 -> profile.getCustomHomeActivity5();
            default -> null;
        };
    }

    private boolean isCustomHeavyAllowed(SimulationProfile profile, int index) {
        return switch (index) {
            case 1 -> Boolean.TRUE.equals(profile.getCustomHomeHeavyAllowed1());
            case 2 -> Boolean.TRUE.equals(profile.getCustomHomeHeavyAllowed2());
            case 3 -> Boolean.TRUE.equals(profile.getCustomHomeHeavyAllowed3());
            case 4 -> Boolean.TRUE.equals(profile.getCustomHomeHeavyAllowed4());
            case 5 -> Boolean.TRUE.equals(profile.getCustomHomeHeavyAllowed5());
            default -> false;
        };
    }

    private int startInsideRange(LocalTime start, LocalTime end, long seed) {
        int startMinute = minuteOfDay(start, 18 * 60);
        int duration = rangeDurationMinutes(start, end);
        int usable = Math.max(30, duration - 90);
        int offset = (int) ((seed / 11) % Math.max(1, usable / 10)) * 10;
        return clampToDay(startMinute + offset);
    }

    private int rangeDurationMinutes(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            return 180;
        }
        int startMinute = minuteOfDay(start, 0);
        int endMinute = minuteOfDay(end, 0);
        int duration = endMinute - startMinute;
        return duration > 0 ? duration : duration + 24 * 60;
    }

    private boolean isInRange(double hour, java.time.LocalTime start, java.time.LocalTime end) {
        if (start == null || end == null) {
            return false;
        }

        double startHour = start.getHour() + start.getMinute() / 60.0;
        double endHour = end.getHour() + end.getMinute() / 60.0;
        if (startHour <= endHour) {
            return hour >= startHour && hour < endHour;
        }
        return hour >= startHour || hour < endHour;
    }

    private double seasonFactor(String type, LocalDateTime time, SimulationProfile profile) {
        int month = time.getMonthValue();
        boolean winter = month == 12 || month <= 2;
        boolean summer = month >= 6 && month <= 8;
        boolean darkSeason = month >= 10 || month <= 3;
        double climate = climateFactor(profile);

        return switch (type) {
            case "HEATER" -> (winter ? 1.28 : summer ? 0.46 : 0.82) / climate;
            case "AC" -> (summer ? 1.3 : winter ? 0.42 : 0.72) * climate;
            case "LIGHT", "LIGHTING" -> darkSeason ? 1.18 : 0.9;
            case "BOILER" -> (winter ? 1.12 : summer ? 0.92 : 1.0) / Math.sqrt(climate);
            case "GAS_BOILER" -> (winter ? 1.65 : summer ? 0.28 : 0.72) / climate;
            default -> 1.0;
        };
    }

    private double climateFactor(SimulationProfile profile) {
        if (profile == null || profile.getCity() == null) {
            return 1.0;
        }

        String city = profile.getCity().trim().toLowerCase();
        if (city.contains("odesa") || city.contains("odessa") || city.contains("одес")
                || city.contains("kherson") || city.contains("херсон")
                || city.contains("mykolaiv") || city.contains("микола")
                || city.contains("закарпат")) {
            return 1.12;
        }
        if (city.contains("lviv") || city.contains("львів")
                || city.contains("ternopil") || city.contains("терноп")
                || city.contains("ivano") || city.contains("івано")
                || city.contains("chernihiv") || city.contains("черніг")
                || city.contains("сум") || city.contains("волин") || city.contains("рівн")) {
            return 0.92;
        }
        return 1.0;
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

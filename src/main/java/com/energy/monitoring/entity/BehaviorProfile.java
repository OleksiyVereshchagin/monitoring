package com.energy.monitoring.entity;

/**
 * Тип поведінки пристрою, який визначає базовий патерн його роботи в генераторі.
 */
public enum BehaviorProfile {
    CONSTANT,
    CYCLIC,
    INTERMITTENT,
    PEAK_BASED
}

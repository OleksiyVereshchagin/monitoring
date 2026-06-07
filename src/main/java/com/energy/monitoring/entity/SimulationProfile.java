package com.energy.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Сутність налаштувань симуляції для групи пристроїв: регіон, присутність, активні дні та часові інтервали.
 */
@Entity
@Table(name = "simulation_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimulationProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "household_id")
    private Household household;

    @Column(nullable = false)
    private Integer occupants;

    @Column(name = "area_m2", nullable = false, precision = 8, scale = 2)
    private BigDecimal areaM2;

    @Column(nullable = false)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_level", nullable = false)
    private ActivityLevel activityLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "presence_mode", nullable = false)
    private PresenceMode presenceMode;

    @Column(name = "sleep_start", nullable = false)
    private LocalTime sleepStart;

    @Column(name = "sleep_end", nullable = false)
    private LocalTime sleepEnd;

    @Column(name = "away_start", nullable = false)
    private LocalTime awayStart;

    @Column(name = "away_end", nullable = false)
    private LocalTime awayEnd;

    @Column(name = "weekend_days", nullable = false)
    private String weekendDays;

    @Column(name = "custom_home_start_1")
    private LocalTime customHomeStart1;

    @Column(name = "custom_home_end_1")
    private LocalTime customHomeEnd1;

    @Enumerated(EnumType.STRING)
    @Column(name = "custom_home_activity_1")
    private ActivityLevel customHomeActivity1;

    @Column(name = "custom_home_heavy_allowed_1")
    private Boolean customHomeHeavyAllowed1;

    @Column(name = "custom_home_start_2")
    private LocalTime customHomeStart2;

    @Column(name = "custom_home_end_2")
    private LocalTime customHomeEnd2;

    @Enumerated(EnumType.STRING)
    @Column(name = "custom_home_activity_2")
    private ActivityLevel customHomeActivity2;

    @Column(name = "custom_home_heavy_allowed_2")
    private Boolean customHomeHeavyAllowed2;

    @Column(name = "custom_home_start_3")
    private LocalTime customHomeStart3;

    @Column(name = "custom_home_end_3")
    private LocalTime customHomeEnd3;

    @Enumerated(EnumType.STRING)
    @Column(name = "custom_home_activity_3")
    private ActivityLevel customHomeActivity3;

    @Column(name = "custom_home_heavy_allowed_3")
    private Boolean customHomeHeavyAllowed3;

    @Column(name = "custom_home_start_4")
    private LocalTime customHomeStart4;

    @Column(name = "custom_home_end_4")
    private LocalTime customHomeEnd4;

    @Enumerated(EnumType.STRING)
    @Column(name = "custom_home_activity_4")
    private ActivityLevel customHomeActivity4;

    @Column(name = "custom_home_heavy_allowed_4")
    private Boolean customHomeHeavyAllowed4;

    @Column(name = "custom_home_start_5")
    private LocalTime customHomeStart5;

    @Column(name = "custom_home_end_5")
    private LocalTime customHomeEnd5;

    @Enumerated(EnumType.STRING)
    @Column(name = "custom_home_activity_5")
    private ActivityLevel customHomeActivity5;

    @Column(name = "custom_home_heavy_allowed_5")
    private Boolean customHomeHeavyAllowed5;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

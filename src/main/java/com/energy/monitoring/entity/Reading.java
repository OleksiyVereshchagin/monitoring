package com.energy.monitoring.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Сутність одного показника споживання пристрою за конкретний момент часу.
 */
@Entity
@Table(name = "readings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "power_consumption", nullable = false, precision = 12, scale = 4)
    private BigDecimal powerConsumption;

    @Column(precision = 12, scale = 4)
    private BigDecimal voltage;

    @Column(precision = 12, scale = 4)
    private BigDecimal current;

    @Column
    @Builder.Default
    private String source = "API";

    @Column(name = "session_id")
    private String sessionId;
}

package com.energy.monitoring.repository;

import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.Reading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReadingRepository extends JpaRepository<Reading, Long>, JpaSpecificationExecutor<Reading> {
    boolean existsByDeviceAndTimestampAfter(Device device, LocalDateTime timestamp);
    boolean existsByDeviceAndTimestamp(Device device, LocalDateTime timestamp);

    @Query("""
            SELECT r.timestamp
            FROM Reading r
            WHERE r.device = :device
            AND r.timestamp BETWEEN :from AND :to
            AND (r.source IS NULL OR r.source <> :excludedSource)
            """)
    List<LocalDateTime> findTimestampsByDeviceAndTimestampBetweenExcludingSource(
            @Param("device") Device device,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("excludedSource") String excludedSource
    );

    @Query("""
            SELECT SUM(r.powerConsumption)
            FROM Reading r
            WHERE r.device IN :devices
            AND (r.source IS NULL OR r.source <> :excludedSource)
            AND r.id = (
                SELECT MAX(latest.id)
                FROM Reading latest
                WHERE latest.device = r.device
                AND latest.timestamp = r.timestamp
                AND (latest.source IS NULL OR latest.source <> :excludedSource)
            )
            GROUP BY r.timestamp
            ORDER BY r.timestamp ASC
            """)
    List<Double> findAggregatedByDevicesOrderByTimestampExcludingSource(
            @Param("devices") List<Device> devices,
            @Param("excludedSource") String excludedSource
    );

    List<Reading> findByDeviceOrderByTimestampAsc(Device device);

    List<Reading> findByDeviceAndSourceNotOrderByTimestampAsc(Device device, String source);

    List<Reading> findByDeviceAndTimestampAfterOrderByTimestampAsc(Device device, LocalDateTime timestamp);

    @Query(value = """
            WITH hours AS (
                SELECT generate_series(
                    date_trunc('hour', CAST(:from AS timestamp)),
                    date_trunc('hour', CAST(CURRENT_TIMESTAMP AS timestamp)),
                    interval '1 hour'
                ) AS hour
            ),
            timestamp_totals AS (
                SELECT
                    date_trunc('hour', r.timestamp) AS hour,
                    r.timestamp,
                    SUM(r.power_consumption) AS total_power
                FROM readings r
                JOIN devices d ON r.device_id = d.id
                WHERE d.user_id = :userId
                AND d.active = true
                AND r.timestamp >= :from
                AND (r.source IS NULL OR r.source <> :excludedSource)
                GROUP BY date_trunc('hour', r.timestamp), r.timestamp
            )
            SELECT hours.hour, AVG(timestamp_totals.total_power)
            FROM hours
            LEFT JOIN timestamp_totals ON timestamp_totals.hour = hours.hour
            GROUP BY hours.hour
            ORDER BY hours.hour ASC
            """,
            nativeQuery = true)
    List<Object[]> findHourlyAggregated(@Param("userId") Long userId,
                                        @Param("from") LocalDateTime from,
                                        @Param("excludedSource") String excludedSource);

    @Query(value = "SELECT SUM(r.power_consumption) FROM readings r " +
            "JOIN devices d ON r.device_id = d.id " +
            "WHERE d.user_id = :userId " +
            "AND r.timestamp >= :from " +
            "AND (r.source IS NULL OR r.source <> :excludedSource)",
            nativeQuery = true)
    Double findTotalConsumptionSince(@Param("userId") Long userId,
                                     @Param("from") LocalDateTime from,
                                     @Param("excludedSource") String excludedSource);

    @Query(value = "SELECT SUM(r.power_consumption) FROM readings r " +
            "JOIN devices d ON r.device_id = d.id " +
            "WHERE d.user_id = :userId " +
            "AND d.active = true " +
            "AND (r.source IS NULL OR r.source <> :excludedSource) " +
            "AND r.timestamp = (" +
            "  SELECT MAX(r2.timestamp) FROM readings r2 " +
            "  JOIN devices d2 ON r2.device_id = d2.id " +
            "  WHERE d2.user_id = :userId " +
            "  AND d2.active = true " +
            "  AND (r2.source IS NULL OR r2.source <> :excludedSource)" +
            ")",
            nativeQuery = true)
    Double findLatestTotalConsumption(@Param("userId") Long userId,
                                      @Param("excludedSource") String excludedSource);
}

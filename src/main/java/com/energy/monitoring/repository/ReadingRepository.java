package com.energy.monitoring.repository;

import com.energy.monitoring.entity.Device;
import com.energy.monitoring.entity.Reading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
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
            """)
    List<LocalDateTime> findTimestampsByDeviceAndTimestampBetween(
            @Param("device") Device device,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query(value = """
            WITH bucketed AS (
                SELECT
                    date_trunc('minute', r.timestamp) AS bucket,
                    r.device_id,
                    AVG(r.power_consumption) AS device_power
                FROM readings r
                JOIN devices d ON r.device_id = d.id
                WHERE d.user_id = :userId
                AND d.active = true
                AND (r.source IS NULL OR r.source <> :excludedSource)
                GROUP BY date_trunc('minute', r.timestamp), r.device_id
            )
            SELECT SUM(device_power)
            FROM bucketed
            GROUP BY bucket
            ORDER BY bucket ASC
            """,
            nativeQuery = true)
    List<Double> findAggregatedByUserIdOrderByTimestamp(@Param("userId") Long userId,
                                                        @Param("excludedSource") String excludedSource);

    List<Reading> findByDeviceAndTimestampAfterOrderByTimestampAsc(Device device, LocalDateTime timestamp);

    @Query(value = """
            WITH hours AS (
                SELECT generate_series(
                    date_trunc('hour', CAST(:from AS timestamp)),
                    date_trunc('hour', CAST(CURRENT_TIMESTAMP AS timestamp)),
                    interval '1 hour'
                ) AS hour
            ),
            bucketed AS (
                SELECT
                    date_trunc('hour', r.timestamp) AS hour,
                    date_trunc('minute', r.timestamp) AS reading_minute,
                    r.device_id,
                    AVG(r.power_consumption) AS device_power
                FROM readings r
                JOIN devices d ON r.device_id = d.id
                WHERE d.user_id = :userId
                AND d.active = true
                AND r.timestamp >= :from
                AND (r.source IS NULL OR r.source <> :excludedSource)
                GROUP BY
                    date_trunc('hour', r.timestamp),
                    date_trunc('minute', r.timestamp),
                    r.device_id
            ),
            totals AS (
                SELECT hour, reading_minute, SUM(device_power) AS total_power
                FROM bucketed
                GROUP BY hour, reading_minute
            )
            SELECT hours.hour, AVG(totals.total_power)
            FROM hours
            LEFT JOIN totals ON totals.hour = hours.hour
            GROUP BY hours.hour
            ORDER BY hours.hour ASC
            """,
            nativeQuery = true)
    List<Object[]> findHourlyAggregated(@Param("userId") Long userId,
                                        @Param("from") LocalDateTime from,
                                        @Param("excludedSource") String excludedSource);

    @Query(value = """
            SELECT SUM(latest.power_consumption)
            FROM (
                SELECT DISTINCT ON (r.device_id)
                    r.device_id,
                    r.power_consumption
                FROM readings r
                JOIN devices d ON r.device_id = d.id
                WHERE d.user_id = :userId
                AND d.active = true
                AND (r.source IS NULL OR r.source <> :excludedSource)
                ORDER BY r.device_id, r.timestamp DESC, r.id DESC
            ) latest
            """,
            nativeQuery = true)
    Double findLatestTotalConsumption(@Param("userId") Long userId,
                                      @Param("excludedSource") String excludedSource);

    @Query(value = """
            SELECT
                d.id,
                d.name,
                d.type,
                SUM(r.power_consumption) * (10.0 / 60.0) AS total_kwh
            FROM readings r
            JOIN devices d ON r.device_id = d.id
            WHERE d.user_id = :userId
            AND d.active = true
            AND r.timestamp >= :from
            AND (r.source IS NULL OR r.source <> :excludedSource)
            GROUP BY d.id, d.name, d.type
            ORDER BY total_kwh DESC
            """,
            nativeQuery = true)
    List<Object[]> findDeviceContribution(@Param("userId") Long userId,
                                          @Param("from") LocalDateTime from,
                                          @Param("excludedSource") String excludedSource);

    @Modifying
    @Query(value = """
            DELETE FROM readings r
            USING devices d
            WHERE r.device_id = d.id
            AND d.user_id = :userId
            AND r.source = :source
            AND r.timestamp BETWEEN :from AND :to
            """,
            nativeQuery = true)
    int deleteGeneratedReadingsForUserBetween(@Param("userId") Long userId,
                                              @Param("source") String source,
                                              @Param("from") LocalDateTime from,
                                              @Param("to") LocalDateTime to);

    @Modifying
    @Query(value = """
            DELETE FROM readings r
            USING devices d
            WHERE r.device_id = d.id
            AND d.user_id = :userId
            AND d.household_id = :householdId
            AND r.source = :source
            AND r.timestamp BETWEEN :from AND :to
            """,
            nativeQuery = true)
    int deleteGeneratedReadingsForUserAndHouseholdBetween(@Param("userId") Long userId,
                                                          @Param("householdId") Long householdId,
                                                          @Param("source") String source,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to);

    @Modifying
    @Query("""
            DELETE FROM Reading r
            WHERE r.device = :device
            AND r.source = :source
            AND r.timestamp BETWEEN :from AND :to
            """)
    int deleteGeneratedReadingsForDeviceBetween(@Param("device") Device device,
                                                @Param("source") String source,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);
}

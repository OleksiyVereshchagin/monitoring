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

    @Query("SELECT SUM(r.powerConsumption) FROM Reading r WHERE r.device IN :devices GROUP BY r.timestamp ORDER BY r.timestamp ASC")
    List<Double> findAggregatedByDevicesOrderByTimestamp(@Param("devices") List<Device> devices);

    List<Reading> findByDeviceAndTimestampAfterOrderByTimestampAsc(Device device, LocalDateTime timestamp);

    @Query(value = "SELECT date_trunc('hour', r.timestamp) as hour, SUM(r.power_consumption) " +
            "FROM readings r " +
            "WHERE r.device_id IN " +
            "(SELECT id FROM devices WHERE user_id = :userId) " +
            "AND r.timestamp >= :from " +
            "GROUP BY date_trunc('hour', r.timestamp) " +
            "ORDER BY hour ASC",
            nativeQuery = true)
    List<Object[]> findHourlyAggregated(@Param("userId") Long userId,
                                        @Param("from") LocalDateTime from);

    @Query(value = "SELECT SUM(r.power_consumption) FROM readings r " +
            "JOIN devices d ON r.device_id = d.id " +
            "WHERE d.user_id = :userId " +
            "AND r.timestamp >= :from",
            nativeQuery = true)
    Double findTotalConsumptionSince(@Param("userId") Long userId,
                                     @Param("from") LocalDateTime from);
}

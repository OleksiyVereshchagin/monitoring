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
}

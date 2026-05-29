package com.energy.monitoring.repository;

import com.energy.monitoring.entity.Anomaly;
import com.energy.monitoring.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AnomalyRepository extends JpaRepository<Anomaly, Long> {
    List<Anomaly> findAllByDeviceInOrderByTimestampDesc(List<Device> devices);
    boolean existsByDeviceAndTimestamp(Device device, LocalDateTime timestamp);
}
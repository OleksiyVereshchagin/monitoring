package com.energy.monitoring.repository;

import com.energy.monitoring.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
    List<Device> findAllByUserIdOrderByCreatedAtDesc(Long userId);
    List<Device> findAllByUserIdAndHouseholdIdOrderByCreatedAtDesc(Long userId, Long householdId);
    Optional<Device> findByIdAndUserId(Long id, Long userId);
    List<Device> findAllByActiveTrue();
}

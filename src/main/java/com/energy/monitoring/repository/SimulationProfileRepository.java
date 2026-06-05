package com.energy.monitoring.repository;

import com.energy.monitoring.entity.SimulationProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SimulationProfileRepository extends JpaRepository<SimulationProfile, Long> {
    Optional<SimulationProfile> findByUserId(Long userId);
}

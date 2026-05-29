package com.energy.monitoring.repository;

import com.energy.monitoring.entity.Household;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HouseholdRepository extends JpaRepository<Household, Long> {
    List<Household> findAllByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<Household> findByIdAndUserId(Long id, Long userId);
    long countByUserId(Long userId);
}

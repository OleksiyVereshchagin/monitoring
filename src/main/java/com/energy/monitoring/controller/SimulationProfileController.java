package com.energy.monitoring.controller;

import com.energy.monitoring.dto.SimulationProfileRequest;
import com.energy.monitoring.dto.SimulationProfileResponse;
import com.energy.monitoring.service.SimulationProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST-контролер для налаштування сценарію споживання окремої групи пристроїв.
 */
@RestController
@RequestMapping("/api/simulation-profile")
@RequiredArgsConstructor
public class SimulationProfileController {

    private final SimulationProfileService simulationProfileService;

    @GetMapping
    public ResponseEntity<SimulationProfileResponse> get(
            @RequestParam(required = false) Long householdId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(simulationProfileService.getOrCreate(householdId, authentication));
    }

    @PutMapping
    public ResponseEntity<SimulationProfileResponse> update(
            @Valid @RequestBody SimulationProfileRequest request,
            @RequestParam(required = false) Long householdId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(simulationProfileService.update(request, householdId, authentication));
    }

    @PostMapping("/defaults")
    public ResponseEntity<SimulationProfileResponse> defaults(
            @RequestParam(required = false) Long householdId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(simulationProfileService.applyDefaults(householdId, authentication));
    }

    @PostMapping("/regenerate-recent")
    public ResponseEntity<Map<String, Object>> regenerateRecent(
            @RequestParam(required = false) Long householdId,
            Authentication authentication
    ) {
        int created = simulationProfileService.regenerateRecent(householdId, authentication);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "created", created
        ));
    }
}

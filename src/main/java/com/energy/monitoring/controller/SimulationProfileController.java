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
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/simulation-profile")
@RequiredArgsConstructor
public class SimulationProfileController {

    private final SimulationProfileService simulationProfileService;

    @GetMapping
    public ResponseEntity<SimulationProfileResponse> get(Authentication authentication) {
        return ResponseEntity.ok(simulationProfileService.getOrCreate(authentication));
    }

    @PutMapping
    public ResponseEntity<SimulationProfileResponse> update(
            @Valid @RequestBody SimulationProfileRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(simulationProfileService.update(request, authentication));
    }

    @PostMapping("/defaults")
    public ResponseEntity<SimulationProfileResponse> defaults(Authentication authentication) {
        return ResponseEntity.ok(simulationProfileService.applyDefaults(authentication));
    }

    @PostMapping("/regenerate-recent")
    public ResponseEntity<Map<String, Object>> regenerateRecent(Authentication authentication) {
        int created = simulationProfileService.regenerateRecent(authentication);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "created", created
        ));
    }
}

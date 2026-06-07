package com.energy.monitoring.controller;

import com.energy.monitoring.dto.DeviceRequest;
import com.energy.monitoring.dto.DeviceResponse;
import com.energy.monitoring.dto.HouseholdRequest;
import com.energy.monitoring.dto.HouseholdResponse;
import com.energy.monitoring.dto.ReadingRequest;
import com.energy.monitoring.dto.ReadingResponse;
import com.energy.monitoring.service.TelemetryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * REST-контролер для груп пристроїв, самих пристроїв і ручних readings.
 */
@RestController
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;

    @PostMapping({"/api/households", "/households"})
    public ResponseEntity<HouseholdResponse> createHousehold(
            @Valid @RequestBody HouseholdRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(telemetryService.createHousehold(request, authentication));
    }

    @GetMapping({"/api/households", "/households"})
    public ResponseEntity<List<HouseholdResponse>> getHouseholds(Authentication authentication) {
        return ResponseEntity.ok(telemetryService.getHouseholds(authentication));
    }

    @PutMapping({"/api/households/{id}", "/households/{id}"})
    public ResponseEntity<HouseholdResponse> updateHousehold(
            @PathVariable Long id,
            @Valid @RequestBody HouseholdRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(telemetryService.updateHousehold(id, request, authentication));
    }

    @DeleteMapping({"/api/households/{id}", "/households/{id}"})
    public ResponseEntity<Void> deleteHousehold(
            @PathVariable Long id,
            Authentication authentication
    ) {
        telemetryService.deleteHousehold(id, authentication);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/devices")
    public ResponseEntity<DeviceResponse> createDevice(
            @Valid @RequestBody DeviceRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(telemetryService.createDevice(request, authentication));
    }

    @GetMapping("/api/devices")
    public ResponseEntity<List<DeviceResponse>> getDevices(
            @RequestParam(required = false) Long householdId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(telemetryService.getDevices(householdId, authentication));
    }

    @PutMapping("/api/devices/{id}")
    public ResponseEntity<DeviceResponse> updateDevice(
            @PathVariable Long id,
            @Valid @RequestBody DeviceRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(telemetryService.updateDevice(id, request, authentication));
    }

    @DeleteMapping("/api/devices/{id}")
    public ResponseEntity<Void> deleteDevice(
            @PathVariable Long id,
            Authentication authentication
    ) {
        telemetryService.deleteDevice(id, authentication);
        return ResponseEntity.noContent().build();
    }

    @PostMapping({"/api/readings", "/readings"})
    public ResponseEntity<ReadingResponse> createReading(
            @Valid @RequestBody ReadingRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(telemetryService.createReading(request, authentication));
    }

    @GetMapping({"/api/readings", "/readings"})
    public ResponseEntity<Page<ReadingResponse>> getReadings(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 20, sort = "timestamp", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                telemetryService.getReadings(deviceId, from, to, pageable, authentication)
        );
    }
}

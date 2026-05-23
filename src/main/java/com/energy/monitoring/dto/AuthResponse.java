package com.energy.monitoring.dto;

public record AuthResponse(
        String token,
        String username
) {}
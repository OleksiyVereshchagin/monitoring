package com.energy.monitoring.exception;

/**
 * Помилка для випадків, коли потрібний ресурс користувача не знайдено.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

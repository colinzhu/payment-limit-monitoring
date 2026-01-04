package com.tvpc.application.service;

import java.util.Collections;
import java.util.List;

/**
 * Result of validation operation
 */
public record ValidationResult(boolean isValid, List<String> errors) {

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public static ValidationResult valid() {
        return new ValidationResult(true, Collections.emptyList());
    }

    public static ValidationResult invalid(String error) {
        return new ValidationResult(false, Collections.singletonList(error));
    }

    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, Collections.unmodifiableList(errors));
    }
}

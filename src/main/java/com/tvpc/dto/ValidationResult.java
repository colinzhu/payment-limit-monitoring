package com.tvpc.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DTO for validation results
 */
public class ValidationResult {
    private final boolean valid;
    private final List<String> errors;

    private ValidationResult(Builder builder) {
        this.valid = builder.valid;
        this.errors = Collections.unmodifiableList(builder.errors);
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    // Static factory methods
    public static ValidationResult valid() {
        return new Builder().valid(true).build();
    }

    public static ValidationResult invalid(String error) {
        return new Builder().valid(false).addError(error).build();
    }

    public static ValidationResult invalid(List<String> errors) {
        return new Builder().valid(false).addErrors(errors).build();
    }

    // Builder pattern
    public static class Builder {
        private boolean valid = true;
        private List<String> errors = new ArrayList<>();

        public Builder valid(boolean valid) {
            this.valid = valid;
            return this;
        }

        public Builder addError(String error) {
            this.errors.add(error);
            this.valid = false;
            return this;
        }

        public Builder addErrors(List<String> errors) {
            this.errors.addAll(errors);
            this.valid = false;
            return this;
        }

        public ValidationResult build() {
            return new ValidationResult(this);
        }
    }

    @Override
    public String toString() {
        return "ValidationResult{" +
                "valid=" + valid +
                ", errors=" + errors +
                '}';
    }
}

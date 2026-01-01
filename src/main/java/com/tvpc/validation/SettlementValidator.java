package com.tvpc.validation;

import com.tvpc.domain.BusinessStatus;
import com.tvpc.domain.SettlementDirection;
import com.tvpc.domain.SettlementType;
import com.tvpc.dto.SettlementRequest;
import com.tvpc.dto.ValidationResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates incoming settlement data
 */
public class SettlementValidator {

    // ISO 4217 currency codes (subset for MVP)
    private static final Set<String> VALID_CURRENCIES = Set.of(
            "USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "CNY", "HKD", "SGD",
            "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "MXN", "BRL", "ZAR", "INR"
    );

    /**
     * Validate a settlement request
     * @param request The settlement request to validate
     * @return ValidationResult with errors if any
     */
    public ValidationResult validate(SettlementRequest request) {
        List<String> errors = new ArrayList<>();

        // Validate required fields
        validateRequiredFields(request, errors);

        // Validate data types and formats
        validateDataTypes(request, errors);

        // Validate enum values
        validateEnumValues(request, errors);

        // Validate business rules
        validateBusinessRules(request, errors);

        if (errors.isEmpty()) {
            return ValidationResult.valid();
        } else {
            return ValidationResult.invalid(errors);
        }
    }

    private void validateRequiredFields(SettlementRequest request, List<String> errors) {
        if (isBlank(request.getSettlementId())) {
            errors.add("settlementId is required");
        }
        if (request.getSettlementVersion() == null) {
            errors.add("settlementVersion is required");
        }
        if (isBlank(request.getPts())) {
            errors.add("pts is required");
        }
        if (isBlank(request.getProcessingEntity())) {
            errors.add("processingEntity is required");
        }
        if (isBlank(request.getCounterpartyId())) {
            errors.add("counterpartyId is required");
        }
        if (isBlank(request.getValueDate())) {
            errors.add("valueDate is required");
        }
        if (isBlank(request.getCurrency())) {
            errors.add("currency is required");
        }
        if (request.getAmount() == null) {
            errors.add("amount is required");
        }
        if (isBlank(request.getBusinessStatus())) {
            errors.add("businessStatus is required");
        }
        if (isBlank(request.getDirection())) {
            errors.add("direction is required");
        }
        if (isBlank(request.getSettlementType())) {
            errors.add("settlementType is required");
        }
    }

    private void validateDataTypes(SettlementRequest request, List<String> errors) {
        // Validate currency code length
        if (request.getCurrency() != null && request.getCurrency().length() != 3) {
            errors.add("currency must be a 3-character ISO 4217 code");
        }

        // Validate currency is in valid list
        if (request.getCurrency() != null && !VALID_CURRENCIES.contains(request.getCurrency())) {
            errors.add("currency " + request.getCurrency() + " is not supported");
        }

        // Validate amount
        if (request.getAmount() != null) {
            if (request.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                errors.add("amount must be non-negative");
            }
            if (request.getAmount().scale() > 2) {
                errors.add("amount must have at most 2 decimal places");
            }
        }

        // Validate value date format
        if (request.getValueDate() != null) {
            try {
                LocalDate.parse(request.getValueDate(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                errors.add("valueDate must be in ISO format (YYYY-MM-DD)");
            }
        }

        // Validate settlement version is a timestamp
        if (request.getSettlementVersion() != null) {
            if (request.getSettlementVersion() <= 0) {
                errors.add("settlementVersion must be a positive timestamp");
            }
            // Check if it's a reasonable timestamp (after year 2000)
            if (request.getSettlementVersion() < 946684800000L) { // 2000-01-01
                errors.add("settlementVersion appears to be invalid (too old)");
            }
        }
    }

    private void validateEnumValues(SettlementRequest request, List<String> errors) {
        // Validate business status
        if (request.getBusinessStatus() != null && !BusinessStatus.isValid(request.getBusinessStatus())) {
            errors.add("businessStatus must be one of: PENDING, INVALID, VERIFIED, CANCELLED");
        }

        // Validate direction
        if (request.getDirection() != null && !SettlementDirection.isValid(request.getDirection())) {
            errors.add("direction must be either PAY or RECEIVE");
        }

        // Validate settlement type
        if (request.getSettlementType() != null && !SettlementType.isValid(request.getSettlementType())) {
            errors.add("settlementType must be either GROSS or NET");
        }
    }

    private void validateBusinessRules(SettlementRequest request, List<String> errors) {
        // Additional business rule validations can be added here
        // For example:
        // - Specific counterparty format requirements
        // - PTS/ProcessingEntity validation against known systems
        // - Amount limits

        // Example: Validate amount is not excessively large
        if (request.getAmount() != null && request.getAmount().compareTo(new BigDecimal("999999999999.99")) > 0) {
            errors.add("amount exceeds maximum allowed value");
        }

        // Example: Validate settlement ID format
        if (request.getSettlementId() != null && request.getSettlementId().length() > 100) {
            errors.add("settlementId exceeds maximum length of 100 characters");
        }
    }

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * Validate a Settlement object (after conversion from request)
     * This is a secondary validation layer
     */
    public ValidationResult validate(com.tvpc.domain.Settlement settlement) {
        List<String> errors = new ArrayList<>();

        if (settlement.getSettlementId() == null) {
            errors.add("settlementId is null");
        }
        if (settlement.getSettlementVersion() == null) {
            errors.add("settlementVersion is null");
        }
        if (settlement.getAmount() == null) {
            errors.add("amount is null");
        }
        if (settlement.getValueDate() == null) {
            errors.add("valueDate is null");
        }

        if (errors.isEmpty()) {
            return ValidationResult.valid();
        } else {
            return ValidationResult.invalid(errors);
        }
    }
}

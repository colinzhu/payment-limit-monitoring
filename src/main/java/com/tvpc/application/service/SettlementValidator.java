package com.tvpc.application.service;

import com.tvpc.application.port.in.SettlementIngestionUseCase.SettlementIngestionCommand;
import com.tvpc.domain.model.BusinessStatus;
import com.tvpc.domain.model.Settlement;
import com.tvpc.domain.model.SettlementDirection;
import com.tvpc.domain.model.SettlementType;

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
     * Validate a settlement ingestion command
     */
    public ValidationResult validate(SettlementIngestionCommand command) {
        List<String> errors = new ArrayList<>();

        validateRequiredFields(command, errors);
        validateDataTypes(command, errors);
        validateEnumValues(command, errors);
        validateBusinessRules(command, errors);

        if (errors.isEmpty()) {
            return ValidationResult.valid();
        } else {
            return ValidationResult.invalid(errors);
        }
    }

    private void validateRequiredFields(SettlementIngestionCommand command, List<String> errors) {
        if (isBlank(command.settlementId())) {
            errors.add("settlementId is required");
        }
        if (command.settlementVersion() == null) {
            errors.add("settlementVersion is required");
        }
        if (isBlank(command.pts())) {
            errors.add("pts is required");
        }
        if (isBlank(command.processingEntity())) {
            errors.add("processingEntity is required");
        }
        if (isBlank(command.counterpartyId())) {
            errors.add("counterpartyId is required");
        }
        if (isBlank(command.valueDate())) {
            errors.add("valueDate is required");
        }
        if (isBlank(command.currency())) {
            errors.add("currency is required");
        }
        if (command.amount() == null) {
            errors.add("amount is required");
        }
        if (isBlank(command.businessStatus())) {
            errors.add("businessStatus is required");
        }
        if (isBlank(command.direction())) {
            errors.add("direction is required");
        }
        if (isBlank(command.settlementType())) {
            errors.add("settlementType is required");
        }
    }

    private void validateDataTypes(SettlementIngestionCommand command, List<String> errors) {
        if (command.currency() != null && command.currency().length() != 3) {
            errors.add("currency must be a 3-character ISO 4217 code");
        }

        if (command.currency() != null && !VALID_CURRENCIES.contains(command.currency())) {
            errors.add("currency " + command.currency() + " is not supported");
        }

        if (command.amount() != null) {
            if (command.amount().compareTo(BigDecimal.ZERO) < 0) {
                errors.add("amount must be non-negative");
            }
            if (command.amount().scale() > 2) {
                errors.add("amount must have at most 2 decimal places");
            }
        }

        if (command.valueDate() != null) {
            try {
                LocalDate.parse(command.valueDate(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                errors.add("valueDate must be in ISO format (YYYY-MM-DD)");
            }
        }

        if (command.settlementVersion() != null) {
            if (command.settlementVersion() <= 0) {
                errors.add("settlementVersion must be a positive timestamp");
            }
            if (command.settlementVersion() < 946684800000L) { // 2000-01-01
                errors.add("settlementVersion appears to be invalid (too old)");
            }
        }
    }

    private void validateEnumValues(SettlementIngestionCommand command, List<String> errors) {
        if (command.businessStatus() != null && !BusinessStatus.isValid(command.businessStatus())) {
            errors.add("businessStatus must be one of: PENDING, INVALID, VERIFIED, CANCELLED");
        }

        if (command.direction() != null && !SettlementDirection.isValid(command.direction())) {
            errors.add("direction must be either PAY or RECEIVE");
        }

        if (command.settlementType() != null && !SettlementType.isValid(command.settlementType())) {
            errors.add("settlementType must be either GROSS or NET");
        }
    }

    private void validateBusinessRules(SettlementIngestionCommand command, List<String> errors) {
        if (command.amount() != null && command.amount().compareTo(new BigDecimal("999999999999.99")) > 0) {
            errors.add("amount exceeds maximum allowed value");
        }

        if (command.settlementId() != null && command.settlementId().length() > 100) {
            errors.add("settlementId exceeds maximum length of 100 characters");
        }
    }

    /**
     * Validate a Settlement domain object
     */
    public ValidationResult validate(Settlement settlement) {
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

    private boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
}

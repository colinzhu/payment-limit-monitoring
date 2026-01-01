package com.tvpc.validation;

import com.tvpc.dto.SettlementRequest;
import com.tvpc.dto.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SettlementValidator
 */
class SettlementValidatorTest {

    private SettlementValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SettlementValidator();
    }

    @Test
    void testValidSettlementRequest() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-12-31",
                "EUR",
                new BigDecimal("1000000.00"),
                "VERIFIED",
                "PAY",
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertTrue(result.isValid(), "Valid settlement should pass validation");
        assertTrue(result.getErrors().isEmpty(), "No errors expected for valid settlement");
    }

    @Test
    void testMissingRequiredFields() {
        SettlementRequest request = new SettlementRequest(
                null,  // missing settlementId
                null,  // missing version
                null,  // missing pts
                null,  // missing processingEntity
                null,  // missing counterpartyId
                null,  // missing valueDate
                null,  // missing currency
                null,  // missing amount
                null,  // missing businessStatus
                null,  // missing direction
                null   // missing settlementType
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with missing fields");
        assertEquals(11, result.getErrors().size(), "Should have 11 errors for missing fields");
    }

    @Test
    void testInvalidCurrency() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-12-31",
                "EURR",  // invalid currency
                new BigDecimal("1000000.00"),
                "VERIFIED",
                "PAY",
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with invalid currency");
        assertTrue(result.getErrors().toString().contains("currency"),
                "Error should mention currency");
    }

    @Test
    void testNegativeAmount() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-12-31",
                "EUR",
                new BigDecimal("-1000.00"),  // negative amount
                "VERIFIED",
                "PAY",
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with negative amount");
    }

    @Test
    void testInvalidDate() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-13-45",  // invalid date
                "EUR",
                new BigDecimal("1000000.00"),
                "VERIFIED",
                "PAY",
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with invalid date format");
    }

    @Test
    void testInvalidDirection() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-12-31",
                "EUR",
                new BigDecimal("1000000.00"),
                "VERIFIED",
                "INVALID",  // invalid direction
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with invalid direction");
    }

    @Test
    void testInvalidBusinessStatus() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-12-31",
                "EUR",
                new BigDecimal("1000000.00"),
                "INVALID_STATUS",  // invalid status
                "PAY",
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with invalid business status");
    }

    @Test
    void testInvalidSettlementType() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-12-31",
                "EUR",
                new BigDecimal("1000000.00"),
                "VERIFIED",
                "PAY",
                "INVALID_TYPE"  // invalid type
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with invalid settlement type");
    }

    @Test
    void testPastDate() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2000-01-01",  // past date (acceptable)
                "EUR",
                new BigDecimal("1000000.00"),
                "VERIFIED",
                "PAY",
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertTrue(result.isValid(), "Past dates are acceptable for settlements");
    }

    @Test
    void testExcessiveAmount() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-12-31",
                "EUR",
                new BigDecimal("9999999999999.99"),  // too large
                "VERIFIED",
                "PAY",
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with excessive amount");
    }

    @Test
    void testInvalidVersion() {
        SettlementRequest request = new SettlementRequest(
                "SETT-12345",
                -1L,  // negative version
                "PTS-A",
                "PE-001",
                "CP-ABC",
                "2025-12-31",
                "EUR",
                new BigDecimal("1000000.00"),
                "VERIFIED",
                "PAY",
                "GROSS"
        );

        ValidationResult result = validator.validate(request);
        assertFalse(result.isValid(), "Should fail with invalid version");
    }
}

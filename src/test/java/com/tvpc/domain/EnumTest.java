package com.tvpc.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for enums
 */
class EnumTest {

    @Test
    void testSettlementDirection() {
        // Test fromValue
        assertEquals(SettlementDirection.PAY, SettlementDirection.fromValue("PAY"));
        assertEquals(SettlementDirection.RECEIVE, SettlementDirection.fromValue("RECEIVE"));

        // Test case insensitivity
        assertEquals(SettlementDirection.PAY, SettlementDirection.fromValue("pay"));
        assertEquals(SettlementDirection.RECEIVE, SettlementDirection.fromValue("receive"));

        // Test isValid
        assertTrue(SettlementDirection.isValid("PAY"));
        assertTrue(SettlementDirection.isValid("RECEIVE"));
        assertFalse(SettlementDirection.isValid("INVALID"));

        // Test getValue
        assertEquals("PAY", SettlementDirection.PAY.getValue());
        assertEquals("RECEIVE", SettlementDirection.RECEIVE.getValue());

        // Test invalid value
        assertThrows(IllegalArgumentException.class, () -> SettlementDirection.fromValue("INVALID"));
    }

    @Test
    void testSettlementType() {
        // Test fromValue
        assertEquals(SettlementType.GROSS, SettlementType.fromValue("GROSS"));
        assertEquals(SettlementType.NET, SettlementType.fromValue("NET"));

        // Test case insensitivity
        assertEquals(SettlementType.GROSS, SettlementType.fromValue("gross"));
        assertEquals(SettlementType.NET, SettlementType.fromValue("net"));

        // Test isValid
        assertTrue(SettlementType.isValid("GROSS"));
        assertTrue(SettlementType.isValid("NET"));
        assertFalse(SettlementType.isValid("INVALID"));

        // Test getValue
        assertEquals("GROSS", SettlementType.GROSS.getValue());
        assertEquals("NET", SettlementType.NET.getValue());

        // Test invalid value
        assertThrows(IllegalArgumentException.class, () -> SettlementType.fromValue("INVALID"));
    }

    @Test
    void testBusinessStatus() {
        // Test fromValue
        assertEquals(BusinessStatus.PENDING, BusinessStatus.fromValue("PENDING"));
        assertEquals(BusinessStatus.INVALID, BusinessStatus.fromValue("INVALID"));
        assertEquals(BusinessStatus.VERIFIED, BusinessStatus.fromValue("VERIFIED"));
        assertEquals(BusinessStatus.CANCELLED, BusinessStatus.fromValue("CANCELLED"));

        // Test case insensitivity
        assertEquals(BusinessStatus.PENDING, BusinessStatus.fromValue("pending"));
        assertEquals(BusinessStatus.VERIFIED, BusinessStatus.fromValue("verified"));

        // Test isValid
        assertTrue(BusinessStatus.isValid("PENDING"));
        assertTrue(BusinessStatus.isValid("INVALID"));
        assertTrue(BusinessStatus.isValid("VERIFIED"));
        assertTrue(BusinessStatus.isValid("CANCELLED"));
        assertFalse(BusinessStatus.isValid("UNKNOWN"));

        // Test getValue
        assertEquals("PENDING", BusinessStatus.PENDING.getValue());
        assertEquals("VERIFIED", BusinessStatus.VERIFIED.getValue());

        // Test invalid value
        assertThrows(IllegalArgumentException.class, () -> BusinessStatus.fromValue("UNKNOWN"));
    }
}

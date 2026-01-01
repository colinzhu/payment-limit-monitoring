package com.tvpc.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Settlement entity
 */
class SettlementTest {

    @Test
    void testSettlementCreation() {
        Settlement settlement = new Settlement(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                LocalDate.of(2025, 12, 31),
                "EUR",
                new BigDecimal("1000000.00"),
                BusinessStatus.VERIFIED,
                SettlementDirection.PAY,
                SettlementType.GROSS
        );

        assertEquals("SETT-12345", settlement.getSettlementId());
        assertEquals(1735689600000L, settlement.getSettlementVersion());
        assertEquals("PTS-A", settlement.getPts());
        assertEquals("PE-001", settlement.getProcessingEntity());
        assertEquals("CP-ABC", settlement.getCounterpartyId());
        assertEquals(LocalDate.of(2025, 12, 31), settlement.getValueDate());
        assertEquals("EUR", settlement.getCurrency());
        assertEquals(new BigDecimal("1000000.00"), settlement.getAmount());
        assertEquals(BusinessStatus.VERIFIED, settlement.getBusinessStatus());
        assertEquals(SettlementDirection.PAY, settlement.getDirection());
        assertEquals(SettlementType.GROSS, settlement.getSettlementType());
        assertFalse(settlement.getIsOld());
    }

    @Test
    void testIsPay() {
        Settlement paySettlement = createSettlement(SettlementDirection.PAY, BusinessStatus.VERIFIED);
        Settlement receiveSettlement = createSettlement(SettlementDirection.RECEIVE, BusinessStatus.VERIFIED);

        assertTrue(paySettlement.isPay());
        assertFalse(paySettlement.isReceive());

        assertFalse(receiveSettlement.isPay());
        assertTrue(receiveSettlement.isReceive());
    }

    @Test
    void testIsCancelled() {
        Settlement cancelled = createSettlement(SettlementDirection.PAY, BusinessStatus.CANCELLED);
        Settlement verified = createSettlement(SettlementDirection.PAY, BusinessStatus.VERIFIED);

        assertTrue(cancelled.isCancelled());
        assertFalse(verified.isCancelled());
    }

    @Test
    void testIsIncludedInRunningTotal() {
        // PAY + VERIFIED = included
        Settlement s1 = createSettlement(SettlementDirection.PAY, BusinessStatus.VERIFIED);
        assertTrue(s1.isIncludedInRunningTotal());

        // PAY + PENDING = included
        Settlement s2 = createSettlement(SettlementDirection.PAY, BusinessStatus.PENDING);
        assertTrue(s2.isIncludedInRunningTotal());

        // PAY + CANCELLED = NOT included
        Settlement s3 = createSettlement(SettlementDirection.PAY, BusinessStatus.CANCELLED);
        assertFalse(s3.isIncludedInRunningTotal());

        // RECEIVE + VERIFIED = NOT included
        Settlement s4 = createSettlement(SettlementDirection.RECEIVE, BusinessStatus.VERIFIED);
        assertFalse(s4.isIncludedInRunningTotal());
    }

    @Test
    void testGetGroupKey() {
        Settlement settlement = createSettlement(SettlementDirection.PAY, BusinessStatus.VERIFIED);
        String groupKey = settlement.getGroupKey();
        assertEquals("PTS-A|PE-001|CP-ABC|2025-12-31", groupKey);
    }

    @Test
    void testEqualsAndHashCode() {
        Settlement s1 = createSettlement(SettlementDirection.PAY, BusinessStatus.VERIFIED);
        Settlement s2 = createSettlement(SettlementDirection.PAY, BusinessStatus.VERIFIED);
        Settlement s3 = createSettlement(SettlementDirection.PAY, BusinessStatus.PENDING);

        // Set IDs to make them equal
        s1.setId(1L);
        s2.setId(1L);
        s3.setId(2L);

        assertEquals(s1, s2);
        assertNotEquals(s1, s3);
        assertEquals(s1.hashCode(), s2.hashCode());
    }

    @Test
    void testToString() {
        Settlement settlement = createSettlement(SettlementDirection.PAY, BusinessStatus.VERIFIED);
        String str = settlement.toString();
        assertTrue(str.contains("Settlement"));
        assertTrue(str.contains("SETT-12345"));
        assertTrue(str.contains("PAY"));
    }

    private Settlement createSettlement(SettlementDirection direction, BusinessStatus status) {
        return new Settlement(
                "SETT-12345",
                1735689600000L,
                "PTS-A",
                "PE-001",
                "CP-ABC",
                LocalDate.of(2025, 12, 31),
                "EUR",
                new BigDecimal("1000000.00"),
                status,
                direction,
                SettlementType.GROSS
        );
    }
}

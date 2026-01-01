package com.tvpc.service.impl;

import com.tvpc.domain.BusinessStatus;
import com.tvpc.domain.SettlementDirection;
import com.tvpc.service.ConfigurationService;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of ConfigurationService
 * For MVP: Fixed 500M limit, static rules
 * Later: Can be extended to fetch from external system
 */
public class InMemoryConfigurationService implements ConfigurationService {

    private static final BigDecimal MVP_LIMIT = new BigDecimal("500000000.00"); // 500M USD
    private final AtomicLong lastRefreshTime = new AtomicLong(System.currentTimeMillis());

    @Override
    public Set<BusinessStatus> getIncludedBusinessStatuses() {
        // From requirements: PENDING, INVALID, VERIFIED are included
        return Set.of(BusinessStatus.PENDING, BusinessStatus.INVALID, BusinessStatus.VERIFIED);
    }

    @Override
    public SettlementDirection getIncludedDirection() {
        // Only PAY settlements contribute to exposure
        return SettlementDirection.PAY;
    }

    @Override
    public BigDecimal getExposureLimit(String counterpartyId) {
        // MVP mode: Fixed limit for all counterparties
        // Advanced mode would fetch from external system based on counterpartyId
        return MVP_LIMIT;
    }

    @Override
    public boolean isMvpMode() {
        // MVP mode uses fixed limit
        // Advanced mode would return false and fetch counterparty-specific limits
        return true;
    }

    @Override
    public void refreshRules() {
        // In MVP mode, rules are static
        // In advanced mode, this would:
        // 1. Call external rule system API
        // 2. Update cached rules
        // 3. Identify affected groups
        // 4. Trigger recalculation for affected groups
        lastRefreshTime.set(System.currentTimeMillis());
    }

    @Override
    public long getLastRuleRefreshTime() {
        return lastRefreshTime.get();
    }
}

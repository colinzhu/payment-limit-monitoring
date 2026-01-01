package com.tvpc.service;

import com.tvpc.domain.BusinessStatus;
import com.tvpc.domain.SettlementDirection;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Configuration service for filtering rules and limits
 */
public interface ConfigurationService {

    /**
     * Get business statuses that should be included in running total calculations
     * Default: PENDING, INVALID, VERIFIED
     */
    Set<BusinessStatus> getIncludedBusinessStatuses();

    /**
     * Get settlement direction that should be included
     * Default: PAY
     */
    SettlementDirection getIncludedDirection();

    /**
     * Get exposure limit for a counterparty
     * @param counterpartyId Counterparty identifier
     * @return Exposure limit in USD
     */
    BigDecimal getExposureLimit(String counterpartyId);

    /**
     * Check if we're in MVP mode (fixed 500M limit) or advanced mode
     */
    boolean isMvpMode();

    /**
     * Refresh rules from external system
     */
    void refreshRules();

    /**
     * Get the time when rules were last refreshed
     */
    long getLastRuleRefreshTime();
}

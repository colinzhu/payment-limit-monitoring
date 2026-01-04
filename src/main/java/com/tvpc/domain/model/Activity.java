package com.tvpc.domain.model;

import lombok.Value;

import java.time.LocalDateTime;

/**
 * Activity - Audit record for approval workflow
 * Entity - Has identity
 */
@Value
public class Activity {
    Long id;
    String pts;
    String processingEntity;
    String settlementId;
    Long settlementVersion;
    String userId;
    String userName;
    String actionType;  // REQUEST_RELEASE, AUTHORISE, etc.
    String actionComment;
    LocalDateTime createTime;
}

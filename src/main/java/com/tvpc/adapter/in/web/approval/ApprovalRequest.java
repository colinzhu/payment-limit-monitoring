package com.tvpc.adapter.in.web.approval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Approval Request DTO - HTTP request for approval workflow
 * Presentation layer DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRequest {
    private String settlementId;
    private Long settlementVersion;
    private String userId;
    private String userName;
    private String comment;
}

package com.tvpc.adapter.in.web.settlementquery;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Settlement Response DTO - Output for settlement queries
 * Application layer DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementQueryResponse {
    // Settlement data
    private Long id;
    private String settlementId;
    private Long settlementVersion;
    private String pts;
    private String processingEntity;
    private String counterpartyId;
    private String valueDate;
    private String currency;
    private BigDecimal amount;
    private String businessStatus;
    private String direction;
    private String settlementType;
    private Boolean isOld;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // Calculated status
    private String calculatedStatus;  // CREATED, BLOCKED, PENDING_AUTHORISE, AUTHORISED

    // Group information (for BLOCKED status)
    private GroupInfo groupInfo;

    // Approval workflow info (for PENDING_AUTHORISE/AUTHORISED)
    private ApprovalInfo approvalInfo;

    // API response fields
    private String status;  // success, error
    private String message;
    private Long sequenceId;

    // Search results
    private Integer totalCount;
    private Integer currentPage;
    private Integer totalPages;

    // Factory methods
    public static SettlementQueryResponse success(String message, Long sequenceId) {
        return SettlementQueryResponse.builder()
                .status("success")
                .message(message)
                .sequenceId(sequenceId)
                .build();
    }

    public static SettlementQueryResponse error(String message) {
        return SettlementQueryResponse.builder()
                .status("error")
                .message(message)
                .build();
    }

    // Inner classes
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupInfo {
        private BigDecimal runningTotal;
        private BigDecimal exposureLimit;
        private Double percentageUsed;
        private Integer settlementCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalInfo {
        private String requesterId;
        private String requesterName;
        private LocalDateTime requestTime;
        private String authorizerId;
        private String authorizerName;
        private LocalDateTime authorizeTime;
        private String comment;
    }
}

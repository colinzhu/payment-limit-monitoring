package com.tvpc.adapter.in.web.recalculate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Recalculate Request DTO - HTTP request for manual recalculation
 * Presentation layer DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecalculateRequest {
    private String pts;
    private String processingEntity;
    private String counterpartyId;
    private String valueDateFrom;
    private String valueDateTo;
    private String reason;
}

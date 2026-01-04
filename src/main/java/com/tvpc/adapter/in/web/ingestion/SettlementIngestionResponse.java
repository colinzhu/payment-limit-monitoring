package com.tvpc.adapter.in.web.ingestion;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for settlement processing response
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettlementIngestionResponse(
        String status,
        String message,
        Long sequenceId,
        List<String> errors
) {
    public static SettlementIngestionResponse success(String message, Long sequenceId) {
        return new SettlementIngestionResponse("success", message, sequenceId, null);
    }

    public static SettlementIngestionResponse error(String message, List<String> errors) {
        return new SettlementIngestionResponse("error", message, null, errors);
    }

    public static SettlementIngestionResponse error(String message) {
        return new SettlementIngestionResponse("error", message, null, null);
    }
}

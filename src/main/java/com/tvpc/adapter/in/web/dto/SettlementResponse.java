package com.tvpc.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for settlement processing response
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettlementResponse(
        String status,
        String message,
        Long sequenceId,
        List<String> errors
) {
    public static SettlementResponse success(String message, Long sequenceId) {
        return new SettlementResponse("success", message, sequenceId, null);
    }

    public static SettlementResponse error(String message, List<String> errors) {
        return new SettlementResponse("error", message, null, errors);
    }

    public static SettlementResponse error(String message) {
        return new SettlementResponse("error", message, null, null);
    }
}

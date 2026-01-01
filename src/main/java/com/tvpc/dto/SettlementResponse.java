package com.tvpc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO for settlement processing response
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SettlementResponse {
    private final String status;  // "success" or "error"
    private final String message;
    private final Long sequenceId;
    private final List<String> errors;

    private SettlementResponse(Builder builder) {
        this.status = builder.status;
        this.message = builder.message;
        this.sequenceId = builder.sequenceId;
        this.errors = builder.errors;
    }

    // Static factory methods
    public static SettlementResponse success(String message, Long sequenceId) {
        return new Builder()
                .status("success")
                .message(message)
                .sequenceId(sequenceId)
                .build();
    }

    public static SettlementResponse error(String message, List<String> errors) {
        return new Builder()
                .status("error")
                .message(message)
                .errors(errors)
                .build();
    }

    public static SettlementResponse error(String message) {
        return new Builder()
                .status("error")
                .message(message)
                .build();
    }

    // Getters
    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Long getSequenceId() {
        return sequenceId;
    }

    public List<String> getErrors() {
        return errors;
    }

    // Builder pattern
    public static class Builder {
        private String status;
        private String message;
        private Long sequenceId;
        private List<String> errors;

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder sequenceId(Long sequenceId) {
            this.sequenceId = sequenceId;
            return this;
        }

        public Builder errors(List<String> errors) {
            this.errors = errors;
            return this;
        }

        public SettlementResponse build() {
            return new SettlementResponse(this);
        }
    }

    @Override
    public String toString() {
        return "SettlementResponse{" +
                "status='" + status + '\'' +
                ", message='" + message + '\'' +
                ", sequenceId=" + sequenceId +
                ", errors=" + errors +
                '}';
    }
}

package com.tvpc.domain.model;

/**
 * Type of settlement processing
 */
public enum SettlementType {
    GROSS("GROSS"),
    NET("NET");

    private final String value;

    SettlementType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SettlementType fromValue(String value) {
        for (SettlementType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown settlement type: " + value);
    }

    public static boolean isValid(String value) {
        for (SettlementType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

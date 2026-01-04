package com.tvpc.domain.model;

/**
 * Direction of a settlement transaction
 */
public enum SettlementDirection {
    PAY("PAY"),
    RECEIVE("RECEIVE");

    private final String value;

    SettlementDirection(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SettlementDirection fromValue(String value) {
        for (SettlementDirection direction : values()) {
            if (direction.value.equalsIgnoreCase(value)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown settlement direction: " + value);
    }

    public static boolean isValid(String value) {
        for (SettlementDirection direction : values()) {
            if (direction.value.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

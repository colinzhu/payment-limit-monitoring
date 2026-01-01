package com.tvpc.domain;

/**
 * Business Status - Settlement status from the PTS system
 */
public enum BusinessStatus {
    PENDING("PENDING"),
    INVALID("INVALID"),
    VERIFIED("VERIFIED"),
    CANCELLED("CANCELLED");

    private final String value;

    BusinessStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static BusinessStatus fromValue(String value) {
        for (BusinessStatus status : BusinessStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown business status: " + value);
    }

    public static boolean isValid(String value) {
        for (BusinessStatus status : BusinessStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}

package com.studentmanagement;

/* ================================================================
   ROLE ENUMERATION (NEW FILE)
   Type-safe user roles for Role-Based Access Control (RBAC).
   Restricts system roles to ADMIN and STUDENT.
   ================================================================ */
public enum Role {
    ADMIN,
    STUDENT;

    /**
     * Safely parses a role string. Defaults to STUDENT on invalid or null values
     * to enforce the principle of least privilege.
     */
    public static Role fromString(String value) {
        if (value == null) return STUDENT;
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return STUDENT;
        }
    }
}

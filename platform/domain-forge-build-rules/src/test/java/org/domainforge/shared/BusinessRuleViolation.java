package org.domainforge.shared;

public class BusinessRuleViolation extends RuntimeException {
    public enum Kind {
        INVALID
    }
}

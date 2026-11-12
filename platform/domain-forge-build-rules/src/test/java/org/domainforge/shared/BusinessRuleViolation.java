package org.domainforge.shared;

import java.io.Serial;

public class BusinessRuleViolation extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public enum Kind {
        INVALID
    }
}

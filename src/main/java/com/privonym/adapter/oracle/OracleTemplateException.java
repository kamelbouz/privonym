package com.privonym.adapter.oracle;

public final class OracleTemplateException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OracleTemplateException(String message) {
        super(message);
    }

    public OracleTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}

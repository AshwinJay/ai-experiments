package com.dbtespresso.sql;

public class SqlValidationException extends RuntimeException {
    public SqlValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

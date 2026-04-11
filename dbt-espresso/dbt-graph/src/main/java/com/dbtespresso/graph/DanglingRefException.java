package com.dbtespresso.graph;

public class DanglingRefException extends RuntimeException {
    public DanglingRefException(String message) { super(message); }
}

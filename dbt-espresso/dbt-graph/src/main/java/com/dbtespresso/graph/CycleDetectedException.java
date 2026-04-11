package com.dbtespresso.graph;

public class CycleDetectedException extends RuntimeException {
    public CycleDetectedException(String message) { super(message); }
}

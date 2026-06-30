package com.eightfold.model;

public record ProvenanceEntry(
    String field,
    String source,
    String method
) {}

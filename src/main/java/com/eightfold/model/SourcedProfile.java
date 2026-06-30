package com.eightfold.model;

public record SourcedProfile(
    String sourceName,
    CanonicalProfile profile
) {}

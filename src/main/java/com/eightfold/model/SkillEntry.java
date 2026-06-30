package com.eightfold.model;

import java.util.List;

public record SkillEntry(
    String name,
    double confidence,
    List<String> sources
) {}

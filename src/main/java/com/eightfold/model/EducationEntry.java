package com.eightfold.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EducationEntry(
    String institution,
    String degree,
    String field,
    @JsonProperty("end_year") Integer endYear
) {}

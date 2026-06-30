package com.eightfold.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OutputConfig(
    List<FieldConfig> fields,
    @JsonProperty("include_confidence") boolean includeConfidence,
    @JsonProperty("include_provenance") boolean includeProvenance,
    @JsonProperty("on_missing") String onMissing,
    @JsonProperty("source_priority") List<String> sourcePriority
) {
    public record FieldConfig(
        String path,
        String type,
        String from,
        boolean required,
        String normalize
    ) {}
}

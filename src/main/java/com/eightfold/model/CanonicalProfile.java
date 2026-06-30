package com.eightfold.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record CanonicalProfile(
    @JsonProperty("candidate_id") String candidateId,
    @JsonProperty("full_name") String fullName,
    List<String> emails,
    List<String> phones,
    Location location,
    Links links,
    String headline,
    @JsonProperty("years_experience") Integer yearsExperience,
    List<SkillEntry> skills,
    List<ExperienceEntry> experience,
    List<EducationEntry> education,
    List<ProvenanceEntry> provenance,
    @JsonProperty("overall_confidence") double overallConfidence,
    
    // Internal metadata maps for tracking field-level attributes in projection
    Map<String, Double> fieldConfidences,
    Map<String, List<String>> fieldSources
) {}

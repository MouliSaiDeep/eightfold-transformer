package com.eightfold.sources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eightfold.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecruiterNotesSource {
    private static final Logger LOGGER = Logger.getLogger(RecruiterNotesSource.class.getName());

    private static final Pattern YEARS_EXP_PATTERN = Pattern.compile(
            "(\\d+)\\s*(?:\\+)?\\s*(?:years of experience|yrs|years in|year of experience|years of exp)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HEADLINE_PATTERN = Pattern.compile(
            "(?:currently a|currently an|works as|is currently a|is currently an)\\s+([^.,\n]+?)(?:\\s+(?:at|in|for)\\b|[.,\n]|$)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(?:based in|located in)\\s+([^.,\n]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final List<String> SKILL_KEYWORDS = List.of(
            "java", "python", "javascript", "ruby", "c++", "html", "css", "sql", "react", "node"
    );

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public RecruiterNotesSource() {
        this.objectMapper = new ObjectMapper();
    }

    public RecruiterNotesSource(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalProfile parseFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                LOGGER.warning("Recruiter notes file not found: " + filePath);
                return null;
            }
            String content = Files.readString(path);
            return parseText(content);
        } catch (Exception e) {
            LOGGER.warning("Failed to parse recruiter notes file: " + filePath + " - " + e.getMessage());
            return null;
        }
    }

    public CanonicalProfile parseText(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }

        Integer yearsExperience = null;
        Matcher expMatcher = YEARS_EXP_PATTERN.matcher(content);
        if (expMatcher.find()) {
            try {
                yearsExperience = Integer.parseInt(expMatcher.group(1));
            } catch (NumberFormatException e) {
            }
        }

        String headline = null;
        Matcher headlineMatcher = HEADLINE_PATTERN.matcher(content);
        if (headlineMatcher.find()) {
            headline = headlineMatcher.group(1).trim();
            if (headline.toLowerCase().startsWith("a ")) {
                headline = headline.substring(2).trim();
            } else if (headline.toLowerCase().startsWith("an ")) {
                headline = headline.substring(3).trim();
            }
        }

        Location location = null;
        Matcher locMatcher = LOCATION_PATTERN.matcher(content);
        if (locMatcher.find()) {
            String rawLoc = locMatcher.group(1).trim();
            location = new Location(rawLoc, null, null);
        }

        List<SkillEntry> skills = new ArrayList<>();
        for (String skill : SKILL_KEYWORDS) {
            Pattern skillPattern = Pattern.compile("\\b" + Pattern.quote(skill) + "\\b", Pattern.CASE_INSENSITIVE);
            if (skillPattern.matcher(content).find()) {
                skills.add(new SkillEntry(skill, 0.5, List.of("Recruiter Notes")));
            }
        }

        List<ExperienceEntry> experience = new ArrayList<>();
        if (yearsExperience != null) {
            int startYear = 2026 - yearsExperience;
            experience.add(new ExperienceEntry(
                    "Prior Experience",
                    "Professional Experience",
                    startYear + "-06",
                    "2026-06",
                    "Extracted from recruiter notes"
            ));
        }

        boolean hasData = yearsExperience != null || headline != null || location != null || !skills.isEmpty();
        if (!hasData) {
            return null;
        }

        List<ProvenanceEntry> provenance = new ArrayList<>();
        if (yearsExperience != null) {
            provenance.add(new ProvenanceEntry("years_experience", "Recruiter Notes", "Regex Extraction"));
            provenance.add(new ProvenanceEntry("experience", "Recruiter Notes", "Synthetic Derivation from Years Experience"));
        }
        if (headline != null) {
            provenance.add(new ProvenanceEntry("headline", "Recruiter Notes", "Regex Extraction"));
        }
        if (location != null) {
            provenance.add(new ProvenanceEntry("location", "Recruiter Notes", "Regex Extraction"));
        }
        if (!skills.isEmpty()) {
            provenance.add(new ProvenanceEntry("skills", "Recruiter Notes", "Keyword Scan"));
        }

        return new CanonicalProfile(
                null,
                null,
                List.of(),
                List.of(),
                location,
                new Links(null, null, null, List.of()),
                headline,
                yearsExperience,
                skills,
                experience,
                List.of(),
                provenance,
                0.0,
                null,
                null
        );
    }
}

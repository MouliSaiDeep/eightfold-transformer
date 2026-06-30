package com.eightfold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eightfold.model.*;
import com.eightfold.sources.CsvSource;
import com.eightfold.sources.GitHubSource;
import com.eightfold.sources.RecruiterNotesSource;
import com.eightfold.normalize.PhoneNormalizer;
import com.eightfold.normalize.LocationNormalizer;
import com.eightfold.normalize.DateNormalizer;
import com.eightfold.normalize.SkillNormalizer;
import com.eightfold.merge.ProfileMerger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
public class Pipeline {
    private static final Logger LOGGER = Logger.getLogger(Pipeline.class.getName());

    public static final List<String> DEFAULT_SOURCE_PRIORITY = List.of(
            "Recruiter CSV",
            "GitHub Profile API",
            "Recruiter Notes"
    );

    private final CsvSource csvSource;
    private final GitHubSource githubSource;
    private final RecruiterNotesSource recruiterNotesSource;
    private final PhoneNormalizer phoneNormalizer;
    private final LocationNormalizer locationNormalizer;
    private final DateNormalizer dateNormalizer;
    private final SkillNormalizer skillNormalizer;
    private final ProfileMerger profileMerger;

    public Pipeline(ObjectMapper objectMapper) {
        this(new CsvSource(), new GitHubSource(objectMapper), new RecruiterNotesSource(objectMapper),
             new PhoneNormalizer(), new LocationNormalizer(), new DateNormalizer(),
             new SkillNormalizer(), new ProfileMerger());
    }

    public Pipeline(CsvSource csvSource, GitHubSource githubSource, PhoneNormalizer phoneNormalizer,
                    LocationNormalizer locationNormalizer, DateNormalizer dateNormalizer,
                    SkillNormalizer skillNormalizer, ProfileMerger profileMerger) {
        this(csvSource, githubSource, new RecruiterNotesSource(), phoneNormalizer,
             locationNormalizer, dateNormalizer, skillNormalizer, profileMerger);
    }

    public Pipeline(CsvSource csvSource, GitHubSource githubSource, RecruiterNotesSource recruiterNotesSource,
                    PhoneNormalizer phoneNormalizer, LocationNormalizer locationNormalizer,
                    DateNormalizer dateNormalizer, SkillNormalizer skillNormalizer,
                    ProfileMerger profileMerger) {
        this.csvSource = csvSource;
        this.githubSource = githubSource;
        this.recruiterNotesSource = recruiterNotesSource;
        this.phoneNormalizer = phoneNormalizer;
        this.locationNormalizer = locationNormalizer;
        this.dateNormalizer = dateNormalizer;
        this.skillNormalizer = skillNormalizer;
        this.profileMerger = profileMerger;
    }

    public List<CanonicalProfile> run(String csvPath, String githubUsername) {
        return run(csvPath, githubUsername, null, DEFAULT_SOURCE_PRIORITY);
    }

    public List<CanonicalProfile> run(String csvPath, String githubUsername, List<String> sourcePriority) {
        return run(csvPath, githubUsername, null, sourcePriority);
    }

    public List<CanonicalProfile> run(String csvPath, String githubUsername, String notesPath, List<String> sourcePriority) {
        // 1. INGEST CSV
        LOGGER.info("Ingesting CSV candidates from: " + csvPath);
        List<CanonicalProfile> csvProfiles = csvSource.parse(csvPath);

        // 2. INGEST GitHub (if provided)
        CanonicalProfile githubProfile = null;
        if (githubUsername != null && !githubUsername.isBlank()) {
            LOGGER.info("Fetching GitHub profile for user: " + githubUsername);
            githubProfile = githubSource.fetchProfile(githubUsername);
        }

        // 3. INGEST Recruiter Notes (if provided)
        CanonicalProfile notesProfile = null;
        String notesContent = null;
        if (notesPath != null && !notesPath.isBlank()) {
            LOGGER.info("Ingesting Recruiter Notes from: " + notesPath);
            try {
                java.nio.file.Path p = java.nio.file.Path.of(notesPath);
                if (java.nio.file.Files.exists(p)) {
                    notesContent = java.nio.file.Files.readString(p);
                    notesProfile = recruiterNotesSource.parseText(notesContent);
                } else {
                    LOGGER.warning("Recruiter notes file not found: " + notesPath);
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to read recruiter notes file: " + notesPath + " - " + e.getMessage());
            }
        }

        // 4. NORMALIZE individual sources
        List<CanonicalProfile> normalizedCsv = csvProfiles.stream()
                .map(this::normalizeProfile)
                .toList();

        CanonicalProfile normalizedGithub = githubProfile != null ? normalizeProfile(githubProfile) : null;
        CanonicalProfile normalizedNotes = notesProfile != null ? normalizeProfile(notesProfile) : null;

        // 5. MERGE & SCORE
        List<CanonicalProfile> mergedProfiles = new ArrayList<>();
        boolean githubMerged = false;

        for (CanonicalProfile csv : normalizedCsv) {
            List<SourcedProfile> sources = new ArrayList<>();
            sources.add(new SourcedProfile("Recruiter CSV", csv));

            if (normalizedGithub != null && shouldMerge(csv, normalizedGithub)) {
                LOGGER.info("Merging GitHub data into candidate: " + csv.fullName());
                sources.add(new SourcedProfile("GitHub Profile API", normalizedGithub));
                githubMerged = true;
            }

            if (normalizedNotes != null && csv.fullName() != null && notesContent != null &&
                    notesContent.toLowerCase().contains(csv.fullName().toLowerCase())) {
                LOGGER.info("Merging Recruiter Notes data into candidate: " + csv.fullName());
                sources.add(new SourcedProfile("Recruiter Notes", normalizedNotes));
            }

            CanonicalProfile merged = profileMerger.merge(sources, sourcePriority);
            mergedProfiles.add(merged);
        }

        // If the GitHub profile couldn't be matched by email or name, and was successfully fetched,
        // we can emit it as its own canonical profile to avoid losing the ingested information.
        if (normalizedGithub != null && !githubMerged) {
            LOGGER.info("GitHub candidate does not match any CSV records. Emitting separate profile for: " + normalizedGithub.fullName());
            List<SourcedProfile> sources = List.of(new SourcedProfile("GitHub Profile API", normalizedGithub));
            mergedProfiles.add(profileMerger.merge(sources, sourcePriority));
        }

        return mergedProfiles;
    }

    private boolean shouldMerge(CanonicalProfile csv, CanonicalProfile github) {
        // Primary Match Key: Normalized lowercase email
        for (String csvEmail : csv.emails()) {
            for (String ghEmail : github.emails()) {
                if (csvEmail.trim().equalsIgnoreCase(ghEmail.trim())) {
                    return true;
                }
            }
        }
        // Fallback Match Key: Case-insensitive name matching to support profile enrichment when email is hidden
        if (csv.fullName() != null && github.fullName() != null) {
            return csv.fullName().strip().equalsIgnoreCase(github.fullName().strip());
        }
        return false;
    }

    private CanonicalProfile normalizeProfile(CanonicalProfile profile) {
        // Normalize phones
        List<String> normalizedPhones = profile.phones().stream()
                .map(phoneNormalizer::normalize)
                .filter(Objects::nonNull)
                .toList();

        // Normalize location
        Location normalizedLocation = locationNormalizer.normalize(profile.location());

        // Normalize experience entry dates
        List<ExperienceEntry> normalizedExperience = profile.experience().stream()
                .map(exp -> new ExperienceEntry(
                        exp.company(),
                        exp.title(),
                        dateNormalizer.normalize(exp.start()),
                        dateNormalizer.normalize(exp.end()),
                        exp.summary()
                ))
                .toList();

        // Normalize skills list
        List<SkillEntry> normalizedSkills = skillNormalizer.normalize(profile.skills());

        return new CanonicalProfile(
                profile.candidateId(),
                profile.fullName(),
                profile.emails(),
                normalizedPhones,
                normalizedLocation,
                profile.links(),
                profile.headline(),
                profile.yearsExperience(),
                normalizedSkills,
                normalizedExperience,
                profile.education(),
                profile.provenance(),
                profile.overallConfidence(),
                profile.fieldConfidences(),
                profile.fieldSources()
        );
    }
}

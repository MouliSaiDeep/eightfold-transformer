package com.eightfold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eightfold.model.*;
import com.eightfold.sources.CsvSource;
import com.eightfold.sources.GitHubSource;
import com.eightfold.sources.RecruiterNotesSource;
import com.eightfold.normalize.PhoneNormalizer;
import com.eightfold.normalize.LocationNormalizer;
import com.eightfold.normalize.DateNormalizer;
import com.eightfold.normalize.SkillNormalizer;
import com.eightfold.merge.ProfileMerger;
import com.eightfold.project.ConfigProjector;
import com.eightfold.validate.OutputValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PipelineTest {

    private ObjectMapper objectMapper;
    private Pipeline pipeline;
    private ConfigProjector projector;
    private OutputValidator validator;

    @BeforeEach
    public void setUp() {
        objectMapper = new ObjectMapper();
        projector = new ConfigProjector(objectMapper);
        validator = new OutputValidator(objectMapper);
    }

    @Test
    public void testHappyPath(@TempDir Path tempDir) throws IOException {
        // Create recruiters.csv with matching record
        Path csvFile = tempDir.resolve("recruiters.csv");
        String csvContent = "name,email,phone,current_company,title\n"
                + "John Doe,john.doe@example.com,+1 (415) 555-2671,Google,Software Engineer\n";
        Files.writeString(csvFile, csvContent);

        // Mock GitHubSource
        GitHubSource mockGitHub = new GitHubSource(objectMapper) {
            @Override
            public CanonicalProfile fetchProfile(String username) {
                if ("johndoe".equals(username)) {
                    return new CanonicalProfile(
                            null,
                            "John Doe",
                            List.of(),
                            List.of(),
                            new Location("San Francisco, CA", null, null),
                            new Links(null, "https://github.com/johndoe", null, List.of()),
                            "Passionate engineer",
                            null,
                            List.of(new SkillEntry("Java", 0.6, List.of("GitHub Profile API"))),
                            List.of(),
                            List.of(),
                            List.of(),
                            0.0,
                            null,
                            null
                    );
                }
                return null;
            }
        };

        pipeline = new Pipeline(new CsvSource(), mockGitHub, new PhoneNormalizer(),
                new LocationNormalizer(), new DateNormalizer(), new SkillNormalizer(), new ProfileMerger());

        List<CanonicalProfile> results = pipeline.run(csvFile.toString(), "johndoe");

        assertEquals(1, results.size());
        CanonicalProfile merged = results.get(0);

        assertEquals("John Doe", merged.fullName());
        assertEquals("+14155552671", merged.phones().get(0)); // Normalized phone
        assertEquals("San Francisco", merged.location().city()); // Normalized location city
        assertEquals("US", merged.location().country()); // Normalized country code
        assertEquals("Software Engineer", merged.headline()); // Priority CSV title over GitHub bio
        assertEquals(0.47, merged.fieldConfidences().get("headline")); // Conflicting headline (0.42 + 0.05 completeness bonus)
        assertEquals(0.91, merged.fieldConfidences().get("full_name")); // Agreeing names
        assertTrue(merged.overallConfidence() > 0.0);
    }

    @Test
    public void testMissingGitHubSource(@TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("recruiters.csv");
        String csvContent = "name,email,phone,current_company,title\n"
                + "Bob Johnson,bob.johnson@example.com,+1 202 555 0143,Netflix,Senior Engineer\n";
        Files.writeString(csvFile, csvContent);

        // GitHub fetch returns null (e.g. rate limit, 404)
        GitHubSource mockGitHub = new GitHubSource(objectMapper) {
            @Override
            public CanonicalProfile fetchProfile(String username) {
                return null; // simulation of API failure
            }
        };

        pipeline = new Pipeline(new CsvSource(), mockGitHub, new PhoneNormalizer(),
                new LocationNormalizer(), new DateNormalizer(), new SkillNormalizer(), new ProfileMerger());

        List<CanonicalProfile> results = pipeline.run(csvFile.toString(), "nonexistent");

        assertEquals(1, results.size());
        CanonicalProfile merged = results.get(0);

        assertEquals("Bob Johnson", merged.fullName());
        assertEquals(0.7, merged.fieldConfidences().get("full_name")); // Single source confidence (Structured source = 0.7)
        assertNull(merged.location()); // GitHub was missing
        assertEquals(0.0, merged.fieldConfidences().get("location"));
    }

    @Test
    public void testMalformedCsvRow(@TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("recruiters.csv");
        String csvContent = "name,email,phone,current_company,title\n"
                + "Invalid Row,,+1 202 555 0143,Meta,Product Manager\n" // Missing email
                + "Valid Row,valid@example.com,+1 202 555 0144,Meta,Product Manager\n";
        Files.writeString(csvFile, csvContent);

        pipeline = new Pipeline(objectMapper); // Uses default real classes with no GitHub call

        List<CanonicalProfile> results = pipeline.run(csvFile.toString(), null);

        assertEquals(1, results.size()); // Succeeded in skipping first row and parsing second
        assertEquals("Valid Row", results.get(0).fullName());
    }

    @Test
    public void testConflictResolution(@TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("recruiters.csv");
        String csvContent = "name,email,phone,current_company,title\n"
                + "CSV Name,conflict@example.com,+1 415 555 2671,Google,Software Engineer\n";
        Files.writeString(csvFile, csvContent);

        // GitHub name is different from CSV name
        GitHubSource mockGitHub = new GitHubSource(objectMapper) {
            @Override
            public CanonicalProfile fetchProfile(String username) {
                return new CanonicalProfile(
                        null,
                        "GitHub Name", // Conflict with "CSV Name"
                        List.of("conflict@example.com"),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0.0,
                        null,
                        null
                );
            }
        };

        pipeline = new Pipeline(new CsvSource(), mockGitHub, new PhoneNormalizer(),
                new LocationNormalizer(), new DateNormalizer(), new SkillNormalizer(), new ProfileMerger());

        List<CanonicalProfile> results = pipeline.run(csvFile.toString(), "conflictuser");

        assertEquals(1, results.size());
        CanonicalProfile merged = results.get(0);

        assertEquals("CSV Name", merged.fullName()); // Priority 1 (CSV) selected
        assertEquals(0.42, merged.fieldConfidences().get("full_name")); // Conflicting values confidence (0.7 * 0.6)
        
        // Provenance records both
        boolean csvRecorded = false;
        boolean ghRecorded = false;
        for (ProvenanceEntry entry : merged.provenance()) {
            if ("full_name".equals(entry.field()) && entry.source().contains("Recruiter CSV")) {
                csvRecorded = true;
            }
            if ("full_name (conflicted_value)".equals(entry.field()) && entry.source().contains("GitHub Profile API")) {
                ghRecorded = true;
            }
        }
        assertTrue(csvRecorded);
        assertTrue(ghRecorded);
    }

    @Test
    public void testCustomProjection(@TempDir Path tempDir) throws IOException {
        // Create recruiters.csv with standard candidate
        Path csvFile = tempDir.resolve("recruiters.csv");
        String csvContent = "name,email,phone,current_company,title\n"
                + "John Doe,john.doe@example.com,+1 (415) 555-2671,Google,Software Engineer\n";
        Files.writeString(csvFile, csvContent);

        // Run pipeline to get canonical record
        pipeline = new Pipeline(objectMapper);
        List<CanonicalProfile> results = pipeline.run(csvFile.toString(), null);
        assertEquals(1, results.size());
        CanonicalProfile canonical = results.get(0);

        // Create a custom OutputConfig mimicking custom.json
        OutputConfig.FieldConfig f1 = new OutputConfig.FieldConfig("full_name", "string", "full_name", true, null);
        OutputConfig.FieldConfig f2 = new OutputConfig.FieldConfig("primary_email", "string", "emails[0]", true, null);
        OutputConfig.FieldConfig f3 = new OutputConfig.FieldConfig("phone", "string", "phones[0]", false, "E.164");
        OutputConfig.FieldConfig f4 = new OutputConfig.FieldConfig("skill_names", "string[]", "skills[].name", false, "canonical");
        
        OutputConfig customConfig = new OutputConfig(List.of(f1, f2, f3, f4), true, true, "null", null);

        // Project candidate
        ObjectNode projected = projector.project(canonical, customConfig);

        // Assert values are wrapped in confidence and provenance nodes
        assertTrue(projected.has("full_name"));
        assertTrue(projected.get("full_name").has("value"));
        assertEquals("John Doe", projected.get("full_name").get("value").asText());
        assertTrue(projected.get("full_name").has("confidence"));
        assertTrue(projected.get("full_name").has("provenance"));

        assertTrue(projected.has("primary_email"));
        assertEquals("john.doe@example.com", projected.get("primary_email").get("value").asText());

        // Validate structure against Schema validator
        com.fasterxml.jackson.databind.node.ArrayNode array = objectMapper.createArrayNode();
        array.add(projected);
        
        assertDoesNotThrow(() -> validator.validate(array, customConfig));
    }

    @Test
    public void testMergeGenericEngine() {
        ProfileMerger merger = new ProfileMerger();

        // Source A (Structured, Priority 1)
        CanonicalProfile profileA = new CanonicalProfile(
                "id1", "Alice Smith", List.of("alice@example.com"), List.of("+14155551111"),
                new Location("New York", "NY", "US"),
                new Links(null, null, null, List.of()),
                "Eng Manager", null, List.of(), List.of(), List.of(), List.of(),
                0.0, Map.of(), Map.of()
        );

        // Source B (Unstructured, Priority 2)
        CanonicalProfile profileB = new CanonicalProfile(
                "id2", "Alice Smith", List.of("alice@example.com"), List.of("+14155551111"),
                new Location("New York", "NY", "US"),
                new Links(null, "github.com/alice", null, List.of()),
                "Tech Lead", null, List.of(), List.of(), List.of(), List.of(),
                0.0, Map.of(), Map.of()
        );

        // Source C (Structured, Priority 3)
        CanonicalProfile profileC = new CanonicalProfile(
                "id3", "Alice Smith", List.of("alice@example.com"), List.of(),
                new Location("New York", "NY", "US"),
                null,
                "Director", null, List.of(), List.of(), List.of(), List.of(),
                0.0, Map.of(), Map.of()
        );

        List<SourcedProfile> sourced = List.of(
                new SourcedProfile("Structured Source A", profileA),
                new SourcedProfile("Unstructured Source B", profileB),
                new SourcedProfile("Structured Source C", profileC)
        );

        List<String> priority = List.of("Structured Source A", "Unstructured Source B", "Structured Source C");

        CanonicalProfile merged = merger.merge(sourced, priority);

        // Assert name agreement (all 3 agree)
        assertEquals("Alice Smith", merged.fullName());
        // base = 0.7 (Source A is structured), mult = 1.3, bonus = 0.0 -> 0.91
        assertEquals(0.91, merged.fieldConfidences().get("full_name"));

        // Assert headline conflict (Source A "Eng Manager" vs B "Tech Lead" vs C "Director")
        assertEquals("Eng Manager", merged.headline()); // Source A wins
        // base = 0.7, mult = 0.6, bonus = 0.0 -> 0.42
        assertEquals(0.42, merged.fieldConfidences().get("headline"));

        // Assert zero sources for skills
        assertTrue(merged.skills().isEmpty());
        assertEquals(0.0, merged.fieldConfidences().get("skills"));
        
        // Zero source provenance is logged
        boolean skillsNoneLogged = merged.provenance().stream()
                .anyMatch(p -> "skills".equals(p.field()) && "none".equals(p.source()) && "no source had this field".equals(p.method()));
        assertTrue(skillsNoneLogged);
    }

    @Test
    public void testRecruiterNotesExtraction() {
        RecruiterNotesSource notesSource = new RecruiterNotesSource();
        String notesText = "Candidate Name: John Doe\n"
                + "John Doe currently works as a Technical Lead at Google.\n"
                + "He has 10 years of experience in web dev.\n"
                + "He is based in New York City.\n"
                + "Skills observed: Java, Python, and SQL.\n";

        CanonicalProfile profile = notesSource.parseText(notesText);
        assertNotNull(profile);
        assertEquals(10, profile.yearsExperience());
        assertEquals("Technical Lead", profile.headline());
        assertEquals("New York City", profile.location().city());
        
        // Check skills
        List<String> skillNames = profile.skills().stream().map(SkillEntry::name).toList();
        assertTrue(skillNames.contains("java"));
        assertTrue(skillNames.contains("python"));
        assertTrue(skillNames.contains("sql"));
        
        // Provenance verification
        boolean yearsProv = profile.provenance().stream()
                .anyMatch(p -> "years_experience".equals(p.field()) && "Recruiter Notes".equals(p.source()));
        assertTrue(yearsProv);
    }

    @Test
    public void testThreeSourceMergeEndToEnd(@TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("recruiters.csv");
        String csvContent = "name,email,phone,current_company,title\n"
                + "John Doe,john.doe@example.com,+1 (415) 555-2671,Google,Software Engineer\n";
        Files.writeString(csvFile, csvContent);

        Path notesFile = tempDir.resolve("john_doe_notes.txt");
        String notesContent = "Candidate Name: John Doe\n"
                + "John Doe currently works as a Technical Lead.\n"
                + "He has 10 years of experience.\n"
                + "He is based in New York City.\n";
        Files.writeString(notesFile, notesContent);

        // Mock GitHubSource
        GitHubSource mockGitHub = new GitHubSource(objectMapper) {
            @Override
            public CanonicalProfile fetchProfile(String username) {
                return new CanonicalProfile(
                        null, "John Doe", List.of("john.doe@example.com"), List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), 0.0, null, null
                );
            }
        };

        // Wire Pipeline
        pipeline = new Pipeline(new CsvSource(), mockGitHub, new RecruiterNotesSource(),
                new PhoneNormalizer(), new LocationNormalizer(), new DateNormalizer(), new SkillNormalizer(), new ProfileMerger());

        List<String> priority = List.of("Recruiter CSV", "GitHub Profile API", "Recruiter Notes");
        List<CanonicalProfile> results = pipeline.run(csvFile.toString(), "johndoe", notesFile.toString(), priority);

        assertEquals(1, results.size());
        CanonicalProfile merged = results.get(0);

        // Verification
        assertEquals("John Doe", merged.fullName());
        assertEquals("Software Engineer", merged.headline());
        assertEquals(10, merged.yearsExperience());
        
        boolean yearsProv = merged.provenance().stream()
                .anyMatch(p -> "years_experience".equals(p.field()) && "Recruiter Notes".equals(p.source()));
        assertTrue(yearsProv);
    }

    @Test
    public void testRecruiterNotesProvenanceMethod() {
        RecruiterNotesSource notesSource = new RecruiterNotesSource();
        String notesText = "Candidate Name: John Doe\n"
                + "He has 10 years of experience.\n"
                + "He is based in New York City.\n"
                + "Skills: Java, Python, and SQL.\n";

        CanonicalProfile notesProfile = notesSource.parseText(notesText);
        ProfileMerger merger = new ProfileMerger();
        
        List<SourcedProfile> sources = List.of(new SourcedProfile("Recruiter Notes", notesProfile));
        List<String> priority = List.of("Recruiter Notes");
        
        CanonicalProfile merged = merger.merge(sources, priority);
        
        // Find location provenance
        boolean locNotesChecked = false;
        boolean skillsNotesChecked = false;
        
        for (ProvenanceEntry p : merged.provenance()) {
            if ("location".equals(p.field())) {
                assertEquals("Recruiter Notes", p.source());
                assertEquals("Regex Extraction", p.method());
                locNotesChecked = true;
            }
            if ("skills".equals(p.field())) {
                assertEquals("Recruiter Notes", p.source());
                assertEquals("Keyword Scan", p.method());
                skillsNotesChecked = true;
            }
        }
        assertTrue(locNotesChecked);
        assertTrue(skillsNotesChecked);
    }

    @Test
    public void testThreeSourceSymmetricProvenance() {
        // Source A (CSV - Structured)
        CanonicalProfile csvProfile = new CanonicalProfile(
                "id1", "Alice Smith", List.of("alice@example.com"), List.of(), null, null, null, null, List.of(), List.of(), List.of(),
                List.of(
                        new ProvenanceEntry("full_name", "Recruiter CSV", "CSV Ingestion"),
                        new ProvenanceEntry("emails", "Recruiter CSV", "CSV Ingestion")
                ),
                0.0, Map.of(), Map.of()
        );

        // Source B (GitHub - Unstructured)
        CanonicalProfile githubProfile = new CanonicalProfile(
                "id2", "Alice Smith", List.of(), List.of(), null, null, "Cool Engineer", null, 
                List.of(new SkillEntry("Java", 0.6, List.of("GitHub Profile API"))), List.of(), List.of(),
                List.of(
                        new ProvenanceEntry("headline", "GitHub Profile API", "GitHub API Fetch"),
                        new ProvenanceEntry("skills", "GitHub Profile API", "GitHub API Fetch")
                ),
                0.0, Map.of(), Map.of()
        );

        // Source C (Recruiter Notes - Unstructured)
        CanonicalProfile notesProfile = new CanonicalProfile(
                "id3", "Alice Smith", List.of(), List.of(), new Location("New York", null, null), null, null, 10, List.of(), List.of(), List.of(),
                List.of(
                        new ProvenanceEntry("years_experience", "Recruiter Notes", "Regex Extraction"),
                        new ProvenanceEntry("location", "Recruiter Notes", "Regex Extraction")
                ),
                0.0, Map.of(), Map.of()
        );

        List<SourcedProfile> sourced = List.of(
                new SourcedProfile("Recruiter CSV", csvProfile),
                new SourcedProfile("GitHub Profile API", githubProfile),
                new SourcedProfile("Recruiter Notes", notesProfile)
        );

        List<String> priority = List.of("Recruiter CSV", "GitHub Profile API", "Recruiter Notes");

        ProfileMerger merger = new ProfileMerger();
        CanonicalProfile merged = merger.merge(sourced, priority);

        // Assert provenance methods match original source methods exactly
        boolean nameChecked = false;
        boolean emailsChecked = false;
        boolean headlineChecked = false;
        boolean skillsChecked = false;
        boolean locationChecked = false;
        boolean yearsChecked = false;

        for (ProvenanceEntry p : merged.provenance()) {
            if ("full_name".equals(p.field())) {
                assertEquals("Recruiter CSV & GitHub Profile API & Recruiter Notes", p.source());
                assertEquals("Merge - Agreeing values", p.method());
                nameChecked = true;
            }
            if ("emails".equals(p.field())) {
                assertEquals("Recruiter CSV", p.source());
                assertEquals("CSV Ingestion", p.method());
                emailsChecked = true;
            }
            if ("headline".equals(p.field())) {
                assertEquals("GitHub Profile API", p.source());
                assertEquals("GitHub API Fetch", p.method());
                headlineChecked = true;
            }
            if ("skills".equals(p.field())) {
                assertEquals("GitHub Profile API", p.source());
                assertEquals("GitHub API Fetch", p.method());
                skillsChecked = true;
            }
            if ("location".equals(p.field())) {
                assertEquals("Recruiter Notes", p.source());
                assertEquals("Regex Extraction", p.method());
                locationChecked = true;
            }
            if ("years_experience".equals(p.field())) {
                assertEquals("Recruiter Notes", p.source());
                assertEquals("Regex Extraction", p.method());
                yearsChecked = true;
            }
        }

        assertTrue(nameChecked);
        assertTrue(emailsChecked);
        assertTrue(headlineChecked);
        assertTrue(skillsChecked);
        assertTrue(locationChecked);
        assertTrue(yearsChecked);
    }
}

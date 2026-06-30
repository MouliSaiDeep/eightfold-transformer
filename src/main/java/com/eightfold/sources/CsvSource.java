package com.eightfold.sources;

import com.eightfold.model.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class CsvSource {
    private static final Logger LOGGER = Logger.getLogger(CsvSource.class.getName());

    public List<CanonicalProfile> parse(String csvFilePath) {
        List<CanonicalProfile> profiles = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .build();

        try (Reader reader = new FileReader(csvFilePath);
             CSVParser parser = new CSVParser(reader, format)) {

            for (CSVRecord record : parser) {
                try {
                    String email = record.isMapped("email") ? record.get("email") : null;
                    if (email == null || email.isBlank()) {
                        LOGGER.warning("Skipping CSV row due to missing/empty email: " + record.toString());
                        continue;
                    }

                    String name = record.isMapped("name") ? record.get("name") : null;
                    String phone = record.isMapped("phone") ? record.get("phone") : null;
                    String currentCompany = record.isMapped("current_company") ? record.get("current_company") : null;
                    String title = record.isMapped("title") ? record.get("title") : null;

                    // Trim whitespace
                    name = name != null ? name.strip() : null;
                    email = email.strip().toLowerCase();
                    phone = phone != null ? phone.strip() : null;
                    currentCompany = currentCompany != null ? currentCompany.strip() : null;
                    title = title != null ? title.strip() : null;

                    // Build links
                    Links links = new Links(null, null, null, List.of());

                    // Build experience entry if company/title present
                    List<ExperienceEntry> experience = new ArrayList<>();
                    if ((currentCompany != null && !currentCompany.isBlank()) || (title != null && !title.isBlank())) {
                        experience.add(new ExperienceEntry(currentCompany, title, null, null, null));
                    }

                    // Build temporary provenance records
                    List<ProvenanceEntry> provenance = new ArrayList<>();
                    if (name != null) provenance.add(new ProvenanceEntry("full_name", "Recruiter CSV", "CSV Ingestion"));
                    provenance.add(new ProvenanceEntry("emails", "Recruiter CSV", "CSV Ingestion"));
                    if (phone != null) provenance.add(new ProvenanceEntry("phones", "Recruiter CSV", "CSV Ingestion"));
                    if (!experience.isEmpty()) {
                        provenance.add(new ProvenanceEntry("experience", "Recruiter CSV", "CSV Ingestion"));
                    }

                    // For headline, we can use title
                    String headline = title;
                    if (headline != null) {
                        provenance.add(new ProvenanceEntry("headline", "Recruiter CSV", "CSV Ingestion"));
                    }

                    CanonicalProfile profile = new CanonicalProfile(
                            null, // id is generated in pipeline
                            name,
                            List.of(email),
                            phone != null && !phone.isBlank() ? List.of(phone) : List.of(),
                            null,
                            links,
                            headline,
                            null,
                            List.of(),
                            experience,
                            List.of(),
                            provenance,
                            0.0,
                            null,
                            null
                    );
                    profiles.add(profile);
                } catch (Exception e) {
                    LOGGER.warning("Skipping CSV row due to parsing exception: " + record.toString() + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to read/parse CSV file: " + csvFilePath + " - " + e.getMessage());
        }

        return profiles;
    }
}

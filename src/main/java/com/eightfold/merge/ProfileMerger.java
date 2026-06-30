package com.eightfold.merge;

import com.eightfold.model.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ProfileMerger {

    private record ResolvedField<T>(
        T value,
        double confidence,
        List<String> sources,
        List<ProvenanceEntry> provenance
    ) {}

    public CanonicalProfile merge(List<SourcedProfile> sourcedProfiles, List<String> sourcePriority) {
        if (sourcedProfiles == null || sourcedProfiles.isEmpty()) {
            return new CanonicalProfile(
                    UUID.randomUUID().toString().substring(0, 16),
                    null, List.of(), List.of(), null, new Links(null, null, null, List.of()),
                    null, null, List.of(), List.of(), List.of(),
                    List.of(new ProvenanceEntry("all", "none", "no sources provided")),
                    0.0, Map.of(), Map.of()
            );
        }

        // Sort SourcedProfiles according to the config priority list
        List<SourcedProfile> sorted = sortByPriority(sourcedProfiles, sourcePriority);

        List<ProvenanceEntry> mergedProvenance = new ArrayList<>();
        Map<String, Double> fieldConfidences = new HashMap<>();
        Map<String, List<String>> fieldSources = new HashMap<>();

        // 1. Full Name
        ResolvedField<String> nameRes = resolveField("full_name", sorted, CanonicalProfile::fullName, null);
        fieldConfidences.put("full_name", nameRes.confidence());
        fieldSources.put("full_name", nameRes.sources());
        mergedProvenance.addAll(nameRes.provenance());

        // 2. Emails
        ResolvedField<List<String>> emailsRes = resolveField("emails", sorted, CanonicalProfile::emails, List.of());
        fieldConfidences.put("emails", emailsRes.confidence());
        fieldSources.put("emails", emailsRes.sources());
        mergedProvenance.addAll(emailsRes.provenance());

        // 3. Phones
        ResolvedField<List<String>> phonesRes = resolveField("phones", sorted, CanonicalProfile::phones, List.of());
        fieldConfidences.put("phones", phonesRes.confidence());
        fieldSources.put("phones", phonesRes.sources());
        mergedProvenance.addAll(phonesRes.provenance());

        // 4. Location
        ResolvedField<Location> locationRes = resolveField("location", sorted, CanonicalProfile::location, null);
        fieldConfidences.put("location", locationRes.confidence());
        fieldSources.put("location", locationRes.sources());
        mergedProvenance.addAll(locationRes.provenance());

        // 5. Links
        ResolvedField<Links> linksRes = resolveField("links", sorted, CanonicalProfile::links, new Links(null, null, null, List.of()));
        fieldConfidences.put("links", linksRes.confidence());
        fieldSources.put("links", linksRes.sources());
        mergedProvenance.addAll(linksRes.provenance());

        // 6. Headline
        ResolvedField<String> headlineRes = resolveField("headline", sorted, CanonicalProfile::headline, null);
        fieldConfidences.put("headline", headlineRes.confidence());
        fieldSources.put("headline", headlineRes.sources());
        mergedProvenance.addAll(headlineRes.provenance());

        // 7. Skills
        ResolvedField<List<SkillEntry>> skillsRes = resolveField("skills", sorted, CanonicalProfile::skills, List.of());
        fieldConfidences.put("skills", skillsRes.confidence());
        fieldSources.put("skills", skillsRes.sources());
        mergedProvenance.addAll(skillsRes.provenance());

        // 8. Experience
        ResolvedField<List<ExperienceEntry>> experienceRes = resolveField("experience", sorted, CanonicalProfile::experience, List.of());
        fieldConfidences.put("experience", experienceRes.confidence());
        fieldSources.put("experience", experienceRes.sources());
        mergedProvenance.addAll(experienceRes.provenance());

        // 9. Education
        ResolvedField<List<EducationEntry>> educationRes = resolveField("education", sorted, CanonicalProfile::education, List.of());
        fieldConfidences.put("education", educationRes.confidence());
        fieldSources.put("education", educationRes.sources());
        mergedProvenance.addAll(educationRes.provenance());

        ResolvedField<Integer> yearsRes = resolveField("years_experience", sorted, CanonicalProfile::yearsExperience, null);
        Integer derivedYears = yearsRes.value();
        double yearsConfidence = yearsRes.confidence();
        List<ProvenanceEntry> yearsProv = new ArrayList<>(yearsRes.provenance());
        List<String> yearsSources = new ArrayList<>(yearsRes.sources());

        if (derivedYears == null) {
            derivedYears = calculateYearsExperience(experienceRes.value());
            if (derivedYears != null) {
                yearsConfidence = 0.6;
                yearsProv.clear();
                yearsProv.add(new ProvenanceEntry("years_experience", "derived", "calculated from experience dates"));
                yearsSources.clear();
                yearsSources.add("derived");
            }
        }

        fieldConfidences.put("years_experience", yearsConfidence);
        fieldSources.put("years_experience", yearsSources);
        mergedProvenance.addAll(yearsProv);

        // Generate Candidate ID
        String email = emailsRes.value().stream()
                .findFirst()
                .orElse(nameRes.value() != null ? nameRes.value() : "no-email")
                .trim()
                .toLowerCase();
        String candidateId = generateCandidateId(email);

        // Calculate overall confidence: average of all field confidence scores for fields that are present
        double totalConfidence = 0.0;
        int presentFieldsCount = 0;
        for (Map.Entry<String, Double> entry : fieldConfidences.entrySet()) {
            if (entry.getValue() > 0.0) {
                totalConfidence += entry.getValue();
                presentFieldsCount++;
            }
        }
        double overallConfidence = presentFieldsCount > 0 
                ? Math.round((totalConfidence / presentFieldsCount) * 100.0) / 100.0 
                : 0.0;

        return new CanonicalProfile(
                candidateId,
                nameRes.value(),
                emailsRes.value(),
                phonesRes.value(),
                locationRes.value(),
                linksRes.value(),
                headlineRes.value(),
                derivedYears,
                skillsRes.value(),
                experienceRes.value(),
                educationRes.value(),
                mergedProvenance,
                overallConfidence,
                fieldConfidences,
                fieldSources
        );
    }

    private List<SourcedProfile> sortByPriority(List<SourcedProfile> profiles, List<String> sourcePriority) {
        List<SourcedProfile> sorted = new ArrayList<>(profiles);
        sorted.sort(Comparator.comparingInt(p -> {
            int index = sourcePriority.indexOf(p.sourceName());
            return index == -1 ? Integer.MAX_VALUE : index;
        }));
        return sorted;
    }

    private <T> ResolvedField<T> resolveField(
            String fieldName,
            List<SourcedProfile> sortedProfiles,
            java.util.function.Function<CanonicalProfile, T> extractor,
            T defaultValue
    ) {
        List<String> contributingSources = new ArrayList<>();
        List<T> values = new ArrayList<>();
        List<SourcedProfile> matchingProfiles = new ArrayList<>();

        for (SourcedProfile sp : sortedProfiles) {
            T val = extractor.apply(sp.profile());
            if (isNotEmpty(val)) {
                contributingSources.add(sp.sourceName());
                values.add(val);
                matchingProfiles.add(sp);
            }
        }

        List<ProvenanceEntry> provenance = new ArrayList<>();

        // Edge Case: Zero contributing sources (visible, not just null)
        if (values.isEmpty()) {
            provenance.add(new ProvenanceEntry(fieldName, "none", "no source had this field"));
            return new ResolvedField<>(defaultValue, 0.0, List.of(), provenance);
        }

        // Sorted by priority, so index 0 is the primary/highest-priority source
        T primaryValue = values.get(0);
        String primarySource = contributingSources.get(0);
        SourcedProfile primarySourcedProfile = matchingProfiles.get(0);

        boolean hasConflict = false;
        for (int i = 1; i < values.size(); i++) {
            if (!valuesAgree(primaryValue, values.get(i))) {
                hasConflict = true;
                provenance.add(new ProvenanceEntry(
                        fieldName + " (conflicted_value)",
                        contributingSources.get(i),
                        "Conflict detected - value ignored in favor of higher priority source"
                ));
            }
        }

        if (contributingSources.size() > 1 && !hasConflict) {
            provenance.add(new ProvenanceEntry(
                    fieldName,
                    String.join(" & ", contributingSources),
                    "Merge - Agreeing values"
            ));
        } else if (hasConflict) {
            String winningMethod = getMethodFromProvenance(primarySourcedProfile.profile(), fieldName, "Merge - Conflicting values");
            provenance.add(new ProvenanceEntry(
                    fieldName,
                    primarySource,
                    winningMethod + " (" + primarySource + " prioritized)"
            ));
        } else {
            String method = getMethodFromProvenance(primarySourcedProfile.profile(), fieldName, "Field Ingestion");
            provenance.add(new ProvenanceEntry(
                    fieldName,
                    primarySource,
                    method
            ));
        }

        double confidence = ConfidenceScorer.calculate(primarySource, contributingSources, hasConflict, primaryValue);

        return new ResolvedField<>(primaryValue, confidence, contributingSources, provenance);
    }

    private String getMethodFromProvenance(CanonicalProfile profile, String fieldName, String defaultFallback) {
        if (profile == null || profile.provenance() == null) {
            return defaultFallback;
        }
        for (ProvenanceEntry entry : profile.provenance()) {
            if (entry.field() != null && 
                (entry.field().equalsIgnoreCase(fieldName) || 
                 entry.field().toLowerCase().startsWith(fieldName.toLowerCase()))) {
                if (entry.method() != null && !entry.method().isBlank()) {
                    return entry.method();
                }
            }
        }
        return defaultFallback;
    }

    private boolean isNotEmpty(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String str) {
            return !str.isBlank();
        }
        if (value instanceof Collection<?> col) {
            return !col.isEmpty();
        }
        if (value instanceof Location loc) {
            return loc.city() != null && !loc.city().isBlank();
        }
        if (value instanceof Links lnk) {
            return lnk.github() != null || lnk.linkedin() != null || lnk.portfolio() != null || (lnk.other() != null && !lnk.other().isEmpty());
        }
        return true;
    }

    private boolean valuesAgree(Object val1, Object val2) {
        if (val1 == val2) {
            return true;
        }
        if (val1 == null || val2 == null) {
            return false;
        }
        if (val1 instanceof String s1 && val2 instanceof String s2) {
            return s1.strip().equalsIgnoreCase(s2.strip());
        }
        if (val1 instanceof List<?> l1 && val2 instanceof List<?> l2) {
            if (l1.size() != l2.size()) {
                return false;
            }
            for (Object item1 : l1) {
                boolean found = false;
                for (Object item2 : l2) {
                    if (valuesAgree(item1, item2)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }
        if (val1 instanceof Location loc1 && val2 instanceof Location loc2) {
            return valuesAgree(loc1.city(), loc2.city())
                    && valuesAgree(loc1.region(), loc2.region())
                    && valuesAgree(loc1.country(), loc2.country());
        }
        if (val1 instanceof Links lnk1 && val2 instanceof Links lnk2) {
            return valuesAgree(lnk1.github(), lnk2.github())
                    && valuesAgree(lnk1.linkedin(), lnk2.linkedin())
                    && valuesAgree(lnk1.portfolio(), lnk2.portfolio())
                    && valuesAgree(lnk1.other(), lnk2.other());
        }
        if (val1 instanceof SkillEntry sk1 && val2 instanceof SkillEntry sk2) {
            return valuesAgree(sk1.name(), sk2.name());
        }
        if (val1 instanceof ExperienceEntry ex1 && val2 instanceof ExperienceEntry ex2) {
            return valuesAgree(ex1.company(), ex2.company())
                    && valuesAgree(ex1.title(), ex2.title());
        }
        if (val1 instanceof EducationEntry ed1 && val2 instanceof EducationEntry ed2) {
            return valuesAgree(ed1.institution(), ed2.institution())
                    && valuesAgree(ed1.degree(), ed2.degree());
        }
        return val1.equals(val2);
    }

    private Integer calculateYearsExperience(List<ExperienceEntry> experience) {
        if (experience == null || experience.isEmpty()) {
            return null;
        }
        double totalMonths = 0;
        for (ExperienceEntry exp : experience) {
            if (exp.start() != null && !exp.start().isBlank()) {
                try {
                    String[] startParts = exp.start().split("-");
                    int startYear = Integer.parseInt(startParts[0]);
                    int startMonth = startParts.length > 1 ? Integer.parseInt(startParts[1]) : 1;

                    int endYear = 2026;
                    int endMonth = 6;
                    if (exp.end() != null && !exp.end().isBlank()) {
                        String[] endParts = exp.end().split("-");
                        endYear = Integer.parseInt(endParts[0]);
                        endMonth = endParts.length > 1 ? Integer.parseInt(endParts[1]) : 1;
                    }

                    int months = (endYear - startYear) * 12 + (endMonth - startMonth);
                    if (months > 0) {
                        totalMonths += months;
                    }
                } catch (Exception e) {
                    // Ignore parsing errors for individual entries
                }
            }
        }
        if (totalMonths == 0) {
            return null;
        }
        return (int) Math.ceil(totalMonths / 12.0);
    }

    private String generateCandidateId(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(email.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16);
        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }
}

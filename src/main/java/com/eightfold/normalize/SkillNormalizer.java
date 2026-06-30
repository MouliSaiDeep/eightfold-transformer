package com.eightfold.normalize;

import com.eightfold.model.SkillEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SkillNormalizer {
    public List<SkillEntry> normalize(List<SkillEntry> rawSkills) {
        if (rawSkills == null) {
            return List.of();
        }

        // Preserve unique skill names in lowercase
        Set<String> seen = new LinkedHashSet<>();
        List<SkillEntry> normalized = new ArrayList<>();
        for (SkillEntry skill : rawSkills) {
            if (skill.name() == null || skill.name().isBlank()) {
                continue;
            }
            String canonicalName = skill.name().trim().toLowerCase();
            if (seen.add(canonicalName)) {
                normalized.add(new SkillEntry(canonicalName, skill.confidence(), skill.sources()));
            }
        }
        return normalized;
    }
}

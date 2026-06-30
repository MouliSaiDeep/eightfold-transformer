package com.eightfold.merge;

import java.util.List;

/**
 * Calculates field confidence score using a documented formula:
 * Confidence = min(1.0, max(0.0, (Base Confidence * Agreement Multiplier) + Completeness Bonus))
 *
 * Rationale:
 * - Base Confidence: Structured inputs (like Recruiter CSV) are assigned a base confidence of 0.7
 *   due to verification checks. Unstructured API inputs (like GitHub profile) get 0.5.
 * - Agreement Multiplier: If all contributing sources agree, confidence is boosted by 1.3x.
 *   If there is a conflict/disagreement, confidence drops to 0.6x. Single sources remain at 1.0x.
 * - Completeness Bonus: Adds +0.05 for rich values (longer strings or lists with multiple entries)
 *   and applies a -0.05 penalty for extremely short/minimal entries (string length < 3).
 */
public class ConfidenceScorer {

    public static double calculate(String primarySource, List<String> contributingSources, boolean hasConflict, Object resolvedValue) {
        if (contributingSources == null || contributingSources.isEmpty()) {
            return 0.0;
        }

        // 1. Base Confidence
        double baseConfidence = getBaseConfidence(primarySource);

        // 2. Agreement Multiplier
        double multiplier;
        if (contributingSources.size() <= 1) {
            multiplier = 1.0;
        } else if (hasConflict) {
            multiplier = 0.6;
        } else {
            multiplier = 1.3;
        }

        // 3. Completeness Bonus
        double bonus = getCompletenessBonus(resolvedValue);

        // Calculate and cap
        double score = (baseConfidence * multiplier) + bonus;
        score = Math.min(1.0, Math.max(0.0, score));

        // Round to 2 decimal places for clean formatting
        return Math.round(score * 100.0) / 100.0;
    }

    private static double getBaseConfidence(String sourceName) {
        if (sourceName == null) {
            return 0.5;
        }
        String lower = sourceName.toLowerCase();
        if (lower.contains("csv") || lower.contains("structured")) {
            return 0.7;
        }
        if (lower.contains("github") || lower.contains("unstructured") || lower.contains("api")) {
            return 0.5;
        }
        return 0.5;
    }

    private static double getCompletenessBonus(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof String str) {
            if (str.isBlank()) return 0.0;
            if (str.length() > 15) return 0.05;
            if (str.length() < 3) return -0.05;
        } else if (value instanceof List<?> list) {
            if (list.size() > 1) return 0.05;
        }
        return 0.0;
    }
}

package com.eightfold.normalize;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DateNormalizer {
    private static final Pattern YYYY_MM_PATTERN = Pattern.compile("^(\\d{4})[-/](\\d{2})$");
    private static final Pattern YYYY_PATTERN = Pattern.compile("^(\\d{4})$");
    private static final Pattern MM_YYYY_PATTERN = Pattern.compile("^(\\d{1,2})[-/](\\d{4})$");

    public String normalize(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        rawDate = rawDate.trim();

        // 1. Matches YYYY-MM or YYYY/MM
        Matcher m1 = YYYY_MM_PATTERN.matcher(rawDate);
        if (m1.matches()) {
            return m1.group(1) + "-" + m1.group(2);
        }

        // 2. Matches YYYY
        Matcher m2 = YYYY_PATTERN.matcher(rawDate);
        if (m2.matches()) {
            return m2.group(1) + "-01";
        }

        // 3. Matches MM-YYYY or MM/YYYY
        Matcher m3 = MM_YYYY_PATTERN.matcher(rawDate);
        if (m3.matches()) {
            String month = m3.group(1);
            if (month.length() == 1) {
                month = "0" + month;
            }
            return m3.group(2) + "-" + month;
        }

        return null;
    }
}

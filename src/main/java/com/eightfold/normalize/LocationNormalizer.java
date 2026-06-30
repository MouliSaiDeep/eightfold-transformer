package com.eightfold.normalize;

import com.eightfold.model.Location;
import java.util.Map;
import java.util.Set;

public class LocationNormalizer {
    private static final Map<String, String> COUNTRY_MAP = Map.ofEntries(
            Map.entry("united states", "US"),
            Map.entry("usa", "US"),
            Map.entry("us", "US"),
            Map.entry("u.s.", "US"),
            Map.entry("u.s.a.", "US"),
            Map.entry("united kingdom", "GB"),
            Map.entry("uk", "GB"),
            Map.entry("u.k.", "GB"),
            Map.entry("england", "GB"),
            Map.entry("india", "IN"),
            Map.entry("canada", "CA"),
            Map.entry("germany", "DE"),
            Map.entry("deutschland", "DE"),
            Map.entry("france", "FR"),
            Map.entry("japan", "JP"),
            Map.entry("australia", "AU"),
            Map.entry("brazil", "BR"),
            Map.entry("brasil", "BR")
    );

    private static final Set<String> US_STATES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", 
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", 
            "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", 
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", 
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY"
    );

    public Location normalize(Location rawLocation) {
        if (rawLocation == null || rawLocation.city() == null || rawLocation.city().isBlank()) {
            return null;
        }

        String raw = rawLocation.city().trim();
        String[] parts = raw.split(",");

        String city = null;
        String region = null;
        String country = null;

        if (parts.length == 1) {
            city = parts[0].trim();
            // Check if the single part is a country itself
            country = resolveCountry(city);
            if (country != null) {
                city = null; // It's just a country name, not a city
            }
        } else if (parts.length == 2) {
            city = parts[0].trim();
            String second = parts[1].trim();

            // Check if second part is country
            country = resolveCountry(second);
            if (country == null) {
                // Check if second part is a US state
                if (US_STATES.contains(second.toUpperCase())) {
                    region = second.toUpperCase();
                    country = "US";
                } else {
                      region = second;
                }
            }
        } else {
            city = parts[0].trim();
            region = parts[1].trim();
            String third = parts[2].trim();

            country = resolveCountry(third);
            if (country == null && US_STATES.contains(region.toUpperCase())) {
                country = "US";
            }
        }

        // Fallback: If we can't parse or parts are empty, store raw string in city
        if (city == null || city.isBlank()) {
            return new Location(raw, null, null);
        }

        return new Location(city, region, country);
    }

    private String resolveCountry(String val) {
        if (val == null) return null;
        String normalized = val.toLowerCase().trim();
        return COUNTRY_MAP.get(normalized);
    }
}

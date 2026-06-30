package com.eightfold.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eightfold.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class GitHubSource {
    private static final Logger LOGGER = Logger.getLogger(GitHubSource.class.getName());
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitHubSource(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = objectMapper;
    }

    public CanonicalProfile fetchProfile(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }

        String profileUrl = "https://api.github.com/users/" + username;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(profileUrl))
                    .header("User-Agent", "Eightfold-Transformer-App")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warning("GitHub API returned status code " + response.statusCode() + " for user " + username);
                return null;
            }

            JsonNode userNode = objectMapper.readTree(response.body());
            String name = userNode.has("name") && !userNode.get("name").isNull() ? userNode.get("name").asText() : null;
            String bio = userNode.has("bio") && !userNode.get("bio").isNull() ? userNode.get("bio").asText() : null;
            String rawLocation = userNode.has("location") && !userNode.get("location").isNull() ? userNode.get("location").asText() : null;
            String htmlUrl = userNode.has("html_url") && !userNode.get("html_url").isNull() ? userNode.get("html_url").asText() : null;
            String reposUrl = userNode.has("repos_url") && !userNode.get("repos_url").isNull() ? userNode.get("repos_url").asText() : null;

            // Fetch languages from repos_url if present
            List<String> languages = fetchLanguages(reposUrl);

            // Map languages to SkillEntry
            List<SkillEntry> skills = new ArrayList<>();
            for (String lang : languages) {
                skills.add(new SkillEntry(lang, 0.6, List.of("GitHub Profile API")));
            }

            // Create profile with location mapping
            Location location = null;
            if (rawLocation != null && !rawLocation.isBlank()) {
                // Keep location raw for now, the normalizer will process it
                location = new Location(rawLocation, null, null);
            }

            Links links = new Links(null, htmlUrl, null, List.of());

            List<ProvenanceEntry> provenance = new ArrayList<>();
            if (name != null) provenance.add(new ProvenanceEntry("full_name", "GitHub Profile API", "GitHub API Fetch"));
            if (location != null) provenance.add(new ProvenanceEntry("location", "GitHub Profile API", "GitHub API Fetch"));
            if (htmlUrl != null) provenance.add(new ProvenanceEntry("links.github", "GitHub Profile API", "GitHub API Fetch"));
            if (bio != null) provenance.add(new ProvenanceEntry("headline", "GitHub Profile API", "GitHub API Fetch"));
            if (!skills.isEmpty()) provenance.add(new ProvenanceEntry("skills", "GitHub Profile API", "GitHub API Fetch"));

            return new CanonicalProfile(
                    null,
                    name,
                    List.of(), // no email in public profile
                    List.of(),
                    location,
                    links,
                    bio,
                    null,
                    skills,
                    List.of(),
                    List.of(),
                    provenance,
                    0.0,
                    null,
                    null
            );
        } catch (Exception e) {
            LOGGER.warning("Failed to fetch GitHub profile for " + username + ": " + e.getMessage());
            return null;
        }
    }

    private List<String> fetchLanguages(String reposUrl) {
        List<String> languages = new ArrayList<>();
        if (reposUrl == null || reposUrl.isBlank()) {
            return languages;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(reposUrl))
                    .header("User-Agent", "Eightfold-Transformer-App")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warning("GitHub Repos API returned status code " + response.statusCode() + " for URL " + reposUrl);
                return languages;
            }

            JsonNode reposNode = objectMapper.readTree(response.body());
            if (reposNode.isArray()) {
                Set<String> uniqueLanguages = new HashSet<>();
                for (JsonNode repo : reposNode) {
                    if (repo.has("language") && !repo.get("language").isNull()) {
                        String lang = repo.get("language").asText().trim();
                        if (!lang.isEmpty()) {
                            uniqueLanguages.add(lang);
                        }
                    }
                }
                languages.addAll(uniqueLanguages);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to fetch GitHub repositories from " + reposUrl + ": " + e.getMessage());
        }

        return languages;
    }
}

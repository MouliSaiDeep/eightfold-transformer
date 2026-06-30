package com.eightfold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eightfold.model.CanonicalProfile;
import com.eightfold.model.OutputConfig;
import com.eightfold.project.ConfigProjector;
import com.eightfold.validate.OutputValidator;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "candidate-transformer", mixinStandardHelpOptions = true, version = "1.0",
        description = "Ingests, normalizes, merges, and projects candidate profile data.")
public class Main implements Callable<Integer> {

    @Option(names = {"--csv"}, required = true, description = "Path to the input recruiter CSV file.")
    private String csvPath;

    @Option(names = {"--github"}, required = false, description = "GitHub username of candidate to fetch.")
    private String githubUsername;

    @Option(names = {"--config"}, required = true, description = "Path to the projection configuration JSON file.")
    private String configPath;

    @Option(names = {"--notes"}, required = false, description = "Path to recruiter notes TXT file.")
    private String notesPath;

    @Option(names = {"--output"}, required = true, description = "Path to write the projected candidate JSON output.")
    private String outputPath;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        // Load configuration
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            System.err.println("Error: Configuration file not found at " + configPath);
            return 1;
        }
        OutputConfig config = objectMapper.readValue(configFile, OutputConfig.class);

        List<String> sourcePriority = config.sourcePriority();
        if (sourcePriority == null || sourcePriority.isEmpty()) {
            sourcePriority = Pipeline.DEFAULT_SOURCE_PRIORITY;
        }

        // Run Pipeline (Ingest, Extract, Normalize, Merge, Score)
        Pipeline pipeline = new Pipeline(objectMapper);
        List<CanonicalProfile> mergedProfiles = pipeline.run(csvPath, githubUsername, notesPath, sourcePriority);

        // Project
        ConfigProjector projector = new ConfigProjector(objectMapper);
        ArrayNode outputArray = objectMapper.createArrayNode();
        for (CanonicalProfile profile : mergedProfiles) {
            try {
                ObjectNode projected = projector.project(profile, config);
                outputArray.add(projected);
            } catch (Exception e) {
                System.err.println("Error projecting candidate " + profile.candidateId() + ": " + e.getMessage());
                return 1;
            }
        }

        // Validate
        OutputValidator validator = new OutputValidator(objectMapper);
        try {
            validator.validate(outputArray, config);
        } catch (Exception e) {
            System.err.println("Validation failed: " + e.getMessage());
            return 1;
        }

        // Emit output JSON
        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        objectMapper.writeValue(outputFile, outputArray);
        System.out.println("Pipeline completed successfully. Output written to " + outputPath);

        return 0;
    }
}

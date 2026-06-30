package com.eightfold.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.eightfold.model.*;

import java.util.ArrayList;
import java.util.List;

public class ConfigProjector {
    private final ObjectMapper objectMapper;

    public ConfigProjector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode project(CanonicalProfile profile, OutputConfig config) {
        ObjectNode rootNode = objectMapper.valueToTree(profile);
        ObjectNode result = objectMapper.createObjectNode();

        boolean wrap = config.includeConfidence() || config.includeProvenance();

        for (OutputConfig.FieldConfig field : config.fields()) {
            String fromPath = field.from();
            if (fromPath == null || fromPath.isBlank()) {
                // If 'from' is omitted, default to using the 'path' as the source path
                fromPath = field.path();
            }

            JsonNode extractedNode = extractValue(rootNode, fromPath);

            // Handle required field validation
            if (field.required() && (extractedNode == null || extractedNode.isNull() || (extractedNode.isArray() && extractedNode.size() == 0))) {
                throw new IllegalArgumentException("Required field '" + field.path() + "' is missing in canonical record for candidate ID " + profile.candidateId());
            }

            // Normalization if requested in config (e.g. "E.164", "canonical")
            if (field.normalize() != null && extractedNode != null && !extractedNode.isNull()) {
                extractedNode = applyRuntimeNormalization(extractedNode, field.normalize());
            }

            // Handle missing values
            if (extractedNode == null || extractedNode.isNull()) {
                if ("omit".equalsIgnoreCase(config.onMissing())) {
                    continue;
                } else if ("default".equalsIgnoreCase(config.onMissing())) {
                    extractedNode = getDefaultValue(field.type());
                } else {
                    extractedNode = objectMapper.nullNode();
                }
            }

            if (wrap) {
                ObjectNode wrapper = objectMapper.createObjectNode();
                wrapper.set("value", extractedNode);

                String rootField = getRootField(fromPath);

                if (config.includeConfidence()) {
                    double confidence = profile.fieldConfidences() != null ? 
                            profile.fieldConfidences().getOrDefault(rootField, 0.0) : 0.0;
                    wrapper.put("confidence", confidence);
                }

                if (config.includeProvenance()) {
                    // Extract provenance entries for this field
                    ArrayNode provArray = objectMapper.createArrayNode();
                    if (profile.provenance() != null) {
                        for (ProvenanceEntry entry : profile.provenance()) {
                            if (entry.field().startsWith(rootField)) {
                                provArray.add(objectMapper.valueToTree(entry));
                            }
                        }
                    }
                    wrapper.set("provenance", provArray);
                }

                result.set(field.path(), wrapper);
            } else {
                result.set(field.path(), extractedNode);
            }
        }

        return result;
    }

    private JsonNode extractValue(JsonNode rootNode, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        // Unsupported JSONPath validation
        if (path.contains("[") && !path.contains("[].") && !path.matches(".+\\[\\d+\\]")) {
            throw new IllegalArgumentException("Unsupported JSONPath syntax in path: " + path);
        }

        // Pluck: skills[].name
        if (path.contains("[].")) {
            int pluckIndex = path.indexOf("[].");
            String arrayField = path.substring(0, pluckIndex);
            String subField = path.substring(pluckIndex + 3);

            JsonNode arrayNode = getNestedNode(rootNode, arrayField);
            if (arrayNode == null || !arrayNode.isArray()) {
                return null;
            }

            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode elem : arrayNode) {
                JsonNode val = getNestedNode(elem, subField);
                if (val != null && !val.isNull()) {
                    result.add(val);
                }
            }
            return result;
        }

        // Index: emails[0]
        if (path.contains("[") && path.endsWith("]")) {
            int openBrace = path.indexOf("[");
            String arrayField = path.substring(0, openBrace);
            String indexStr = path.substring(openBrace + 1, path.length() - 1);
            try {
                int index = Integer.parseInt(indexStr);
                JsonNode arrayNode = getNestedNode(rootNode, arrayField);
                if (arrayNode == null || !arrayNode.isArray() || index < 0 || index >= arrayNode.size()) {
                    return null;
                }
                return arrayNode.get(index);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid array index in path: " + path);
            }
        }

        // Direct
        return getNestedNode(rootNode, path);
    }

    private JsonNode getNestedNode(JsonNode node, String dottedPath) {
        if (node == null || dottedPath == null || dottedPath.isBlank()) {
            return null;
        }
        String[] parts = dottedPath.split("\\.");
        JsonNode current = node;
        for (String part : parts) {
            if (current == null || current.isNull()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private String getRootField(String path) {
        if (path.contains("[")) {
            return path.substring(0, path.indexOf("["));
        }
        if (path.contains(".")) {
            return path.substring(0, path.indexOf("."));
        }
        return path;
    }

    private JsonNode getDefaultValue(String type) {
        if (type == null) return objectMapper.nullNode();
        return switch (type.toLowerCase()) {
            case "string" -> objectMapper.valueToTree("");
            case "integer", "int" -> objectMapper.valueToTree(0);
            case "number", "double" -> objectMapper.valueToTree(0.0);
            case "boolean" -> objectMapper.valueToTree(false);
            case "string[]" -> objectMapper.createArrayNode();
            default -> objectMapper.nullNode();
        };
    }

    private JsonNode applyRuntimeNormalization(JsonNode node, String normalization) {
        if (node == null || node.isNull()) {
            return node;
        }
        if ("canonical".equalsIgnoreCase(normalization)) {
            if (node.isArray()) {
                ArrayNode array = objectMapper.createArrayNode();
                for (JsonNode item : node) {
                    array.add(item.asText().trim().toLowerCase());
                }
                return array;
            } else {
                return objectMapper.valueToTree(node.asText().trim().toLowerCase());
            }
        } else if ("E.164".equalsIgnoreCase(normalization)) {
            if (node.isTextual() && !node.asText().startsWith("+")) {
                return objectMapper.nullNode();
            }
            return node;
        }
        return node;
    }
}

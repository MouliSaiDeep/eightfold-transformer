package com.eightfold.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.eightfold.model.OutputConfig;

import java.util.Set;

public class OutputValidator {
    private final ObjectMapper objectMapper;

    public OutputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(JsonNode outputJsonArray, OutputConfig config) {
        if (!outputJsonArray.isArray()) {
            throw new IllegalArgumentException("Output must be a JSON array of candidates");
        }

        // 1. Generate JSON Schema dynamically based on OutputConfig
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "array");

        ObjectNode itemsSchema = objectMapper.createObjectNode();
        itemsSchema.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode requiredFields = objectMapper.createArrayNode();

        boolean wrap = config.includeConfidence() || config.includeProvenance();

        for (OutputConfig.FieldConfig field : config.fields()) {
            ObjectNode fieldSchema = objectMapper.createObjectNode();
            
            // Build type node for the raw value
            ObjectNode valueSchema = objectMapper.createObjectNode();
            mapType(valueSchema, field.type(), field.required());

            if (wrap) {
                fieldSchema.put("type", "object");
                ObjectNode fieldProps = objectMapper.createObjectNode();
                fieldProps.set("value", valueSchema);
                
                if (config.includeConfidence()) {
                    ObjectNode conf = objectMapper.createObjectNode();
                    conf.put("type", "number");
                    conf.put("minimum", 0.0);
                    conf.put("maximum", 1.0);
                    fieldProps.set("confidence", conf);
                }
                
                if (config.includeProvenance()) {
                    ObjectNode prov = objectMapper.createObjectNode();
                    prov.put("type", "array");
                    ObjectNode provItem = objectMapper.createObjectNode();
                    provItem.put("type", "object");
                    prov.set("items", provItem);
                    fieldProps.set("provenance", prov);
                }
                
                fieldSchema.set("properties", fieldProps);
                
                if (field.required()) {
                    ArrayNode fieldRequired = objectMapper.createArrayNode();
                    fieldRequired.add("value");
                    fieldSchema.set("required", fieldRequired);
                }
            } else {
                fieldSchema = valueSchema;
            }

            properties.set(field.path(), fieldSchema);

            if (field.required()) {
                requiredFields.add(field.path());
            }
        }

        itemsSchema.set("properties", properties);
        if (requiredFields.size() > 0) {
            itemsSchema.set("required", requiredFields);
        }

        schema.set("items", itemsSchema);

        // 2. Validate using networknt json-schema-validator
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        JsonSchema jsonSchema = factory.getSchema(schema);

        Set<ValidationMessage> errors = jsonSchema.validate(outputJsonArray);
        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Output validation failed against generated schema:\n");
            for (ValidationMessage msg : errors) {
                sb.append("- ").append(msg.getMessage()).append("\n");
            }
            throw new IllegalStateException(sb.toString());
        }
    }

    private void mapType(ObjectNode node, String type, boolean required) {
        if (type == null) {
            if (!required) {
                ArrayNode types = objectMapper.createArrayNode();
                types.add("string");
                types.add("null");
                node.set("type", types);
            } else {
                node.put("type", "string");
            }
            return;
        }
        
        String typeLower = type.toLowerCase();
        if ("string[]".equals(typeLower)) {
            if (!required) {
                ArrayNode types = objectMapper.createArrayNode();
                types.add("array");
                types.add("null");
                node.set("type", types);
            } else {
                node.put("type", "array");
            }
            ObjectNode items = objectMapper.createObjectNode();
            items.put("type", "string");
            node.set("items", items);
            return;
        }

        String mappedType;
        switch (typeLower) {
            case "integer":
            case "int":
                mappedType = "integer";
                break;
            case "number":
            case "double":
                mappedType = "number";
                break;
            case "boolean":
                mappedType = "boolean";
                break;
            default:
                mappedType = "string";
        }

        if (!required) {
            ArrayNode types = objectMapper.createArrayNode();
            types.add(mappedType);
            types.add("null");
            node.set("type", types);
        } else {
            node.put("type", mappedType);
        }
    }
}

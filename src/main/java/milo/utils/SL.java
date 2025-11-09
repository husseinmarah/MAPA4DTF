package milo.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SL (Semantic Language) utility for FIPA-SL content parsing and generation
 * Enhanced to support FAP protocol messages
 * Helper methods for backward compatibility
 */
public class SL {

    // ========== ORIGINAL PARSING METHOD ==========

    /**
     * Tiny helper to extract simple SL-like fields from message content
     * Original method preserved for backward compatibility
     *
     * @param s the source string
     * @param a the start delimiter
     * @param b the end delimiter
     * @return the extracted substring or null if not found
     */
    public static String ex(String s, String a, String b) {
        int i = s.indexOf(a);
        if (i < 0) return null;
        i += a.length();
        int j = s.indexOf(b, i);
        if (j < 0) return null;
        return s.substring(i, j);
    }

    // ========== ENHANCED PARSING METHODS ==========

    /**
     * Parse parameters from SL format content
     * Example: "(action :param1 value1 :param2 \"value2\")"
     */
    public static Map<String, String> parseAction(String slContent) {
        Map<String, String> params = new HashMap<>();

        if (slContent == null || slContent.trim().isEmpty()) {
            return params;
        }

        // Remove outer parentheses if present
        String content = slContent.trim();
        if (content.startsWith("(") && content.endsWith(")")) {
            content = content.substring(1, content.length() - 1);
        }

        // Extract action name
        String[] parts = content.split("\\s+", 2);
        if (parts.length > 0) {
            params.put("action", parts[0]);
        }

        // Parse parameters
        if (parts.length > 1) {
            parseParameters(parts[1], params);
        }

        return params;
    }

    /**
     * Parse FAP parameters from SL content
     * Supports patterns like: (fap-alloc :geo "uantwerp" :domain "production" :capability "transport")
     */
    public static Map<String, String> parseParameters(String slContent) {
        Map<String, String> params = new HashMap<>();
        parseParameters(slContent, params);
        return params;
    }

    /**
     * Internal parameter parsing method
     */
    private static void parseParameters(String content, Map<String, String> params) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }

        // Pattern to match :key "value" or :key value pairs
        Pattern pattern = Pattern.compile(":([a-zA-Z0-9_-]+)\\s+(?:\"([^\"]*)\"|([^\\s:]+))");
        Matcher matcher = pattern.matcher(content);

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            params.put(key, value);
        }
    }

    // ========== FAP PROTOCOL MESSAGE CREATION ==========

    /**
     * Create FAP allocation request in SL format
     */
    public static String createFAPAllocRequest(String geo, String domain, String level,
                                               String system, String component,
                                               String capability, String qos) {
        StringBuilder sb = new StringBuilder();
        sb.append("(fap-alloc");

        if (geo != null) sb.append(" :geo \"").append(geo).append("\"");
        if (domain != null) sb.append(" :domain \"").append(domain).append("\"");
        if (level != null) sb.append(" :level \"").append(level).append("\"");
        if (system != null) sb.append(" :system \"").append(system).append("\"");
        if (component != null) sb.append(" :component \"").append(component).append("\"");
        if (capability != null) sb.append(" :capability \"").append(capability).append("\"");
        if (qos != null) sb.append(" :qos \"").append(qos).append("\"");

        sb.append(")");
        return sb.toString();
    }

    /**
     * Create FAP resolve request in SL format
     */
    public static String createFAPResolveRequest(String ffa) {
        return "(fap-resolve :ffa \"" + ffa + "\")";
    }

    /**
     * Create FAP update request in SL format
     */
    public static String createFAPUpdateRequest() {
        return "(fap-update)";
    }

    /**
     * Parse FAP response content
     */
    public static Map<String, String> parseFAPResponse(String responseContent) {
        Map<String, String> response = new HashMap<>();

        if (responseContent == null) {
            return response;
        }

        // Extract result type
        if (responseContent.contains("Assigned-FFA")) {
            response.put("result", "SUCCESS");
            response.put("operation", "ALLOC");
        } else if (responseContent.contains("Resolved")) {
            response.put("result", "SUCCESS");
            response.put("operation", "RESOLVE");
        } else if (responseContent.contains("Updated")) {
            response.put("result", "SUCCESS");
            response.put("operation", "UPDATE");
        } else if (responseContent.contains("Failure")) {
            response.put("result", "FAILURE");
        } else if (responseContent.contains("Not-Found")) {
            response.put("result", "NOT_FOUND");
        } else if (responseContent.contains("Expired")) {
            response.put("result", "EXPIRED");
        }

        // Parse specific values using the parameter parser
        Map<String, String> params = parseParameters(responseContent);
        response.putAll(params);

        return response;
    }

    // ========== FEDERATION SERVICE MESSAGES ==========

    /**
     * Create service registration request in SL format
     */
    public static String createServiceRegistrationRequest(String serviceName, String serviceType,
                                                          String ffa, String capability, String qos) {
        StringBuilder sb = new StringBuilder();
        sb.append("(register-federated-service");

        if (serviceName != null) sb.append(" :name \"").append(serviceName).append("\"");
        if (serviceType != null) sb.append(" :type \"").append(serviceType).append("\"");
        if (ffa != null) sb.append(" :ffa \"").append(ffa).append("\"");
        if (capability != null) sb.append(" :capability \"").append(capability).append("\"");
        if (qos != null) sb.append(" :qos \"").append(qos).append("\"");

        sb.append(")");
        return sb.toString();
    }

    /**
     * Create service query request in SL format
     */
    public static String createServiceQueryRequest(String ffaPattern, String serviceType,
                                                   String capability, boolean includeRemote) {
        StringBuilder sb = new StringBuilder();
        sb.append("(query-federated-services");

        if (ffaPattern != null) sb.append(" :ffa-pattern \"").append(ffaPattern).append("\"");
        if (serviceType != null) sb.append(" :service-type \"").append(serviceType).append("\"");
        if (capability != null) sb.append(" :capability \"").append(capability).append("\"");
        sb.append(" :include-remote ").append(includeRemote);

        sb.append(")");
        return sb.toString();
    }

    // ========== FEDERATION SYSTEM MESSAGES ==========

    /**
     * Create heartbeat message in SL format
     */
    public static String createHeartbeatMessage(String timestamp) {
        return "(federation-heartbeat :timestamp \"" + timestamp + "\")";
    }

    /**
     * Create workflow orchestration message in SL format
     */
    public static String createWorkflowMessage(String workflowId, String operation, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("(workflow-orchestration");

        if (workflowId != null) sb.append(" :workflow-id \"").append(workflowId).append("\"");
        if (operation != null) sb.append(" :operation \"").append(operation).append("\"");
        if (context != null) sb.append(" :context \"").append(context).append("\"");

        sb.append(")");
        return sb.toString();
    }

    // ========== UTILITY METHODS ==========

    /**
     * Extract field using original tiny helper method (alternative to ex method)
     * Convenience method with more descriptive name
     */
    public static String extractField(String source, String startDelim, String endDelim) {
        return ex(source, startDelim, endDelim);
    }

    /**
     * Extract quoted value using original helper
     * Example: extractQuotedValue(":ffa \"value\"", ":ffa \"", "\"") -> "value"
     */
    public static String extractQuotedValue(String source, String prefix) {
        String startDelim = prefix + "\"";
        String endDelim = "\"";
        return ex(source, startDelim, endDelim);
    }

    /**
     * Escape quotes in SL strings
     */
    public static String escapeString(String input) {
        if (input == null) return null;
        return input.replace("\"", "\\\"");
    }

    /**
     * Unescape quotes in SL strings
     */
    public static String unescapeString(String input) {
        if (input == null) return null;
        return input.replace("\\\"", "\"");
    }

    /**
     * Validate SL format
     */
    public static boolean isValidSL(String slContent) {
        if (slContent == null || slContent.trim().isEmpty()) {
            return false;
        }

        String trimmed = slContent.trim();
        return trimmed.startsWith("(") && trimmed.endsWith(")");
    }

    /**
     * Extract action name from SL content
     */
    public static String extractAction(String slContent) {
        Map<String, String> parsed = parseAction(slContent);
        return parsed.get("action");
    }

    /**
     * Simple field extraction (backward compatibility with original style)
     * Extract value between two strings, similar to original ex method but with error handling
     */
    public static String extract(String source, String start, String end) {
        if (source == null || start == null || end == null) {
            return null;
        }

        try {
            return ex(source, start, end);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract FFA from response content using original helper style
     */
    public static String extractFFA(String responseContent) {
        return ex(responseContent, ":ffa \"", "\"");
    }

    /**
     * Extract AID from response content using original helper style
     */
    public static String extractAID(String responseContent) {
        return ex(responseContent, ":aid \"", "\"");
    }

    /**
     * Extract reason from failure response using original helper style
     */
    public static String extractReason(String responseContent) {
        return ex(responseContent, ":reason \"", "\"");
    }
}
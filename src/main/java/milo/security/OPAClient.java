package milo.security;

import milo.federation.FederationHelper;
import okhttp3.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * OPA (Open Policy Agent) Client
 * 
 * Integrates with OPA to enforce authorization policies defined in policy.rego.
 * Evaluates policy decisions for agent communications, federation access, and resource usage.
 */
public class OPAClient {
    
    private final String opaUrl;
    private final OkHttpClient httpClient;
    private static final String DEFAULT_OPA_URL = "http://localhost:8181/v1/data/authz/allow";

    private java.util.Map<String, String> componentRoles;
    private JSONArray federationRules;
    
    /**
     * Create OPA client with default URL
     */
    public OPAClient() {
        this(DEFAULT_OPA_URL);
    }
    
    /**
     * Create OPA client with custom URL
     * @param opaUrl URL to OPA policy evaluation endpoint
     */
    public OPAClient(String opaUrl) {
        this.opaUrl = opaUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
        
        loadFederationConfig();
    }

    /**
     * Loads federation roles and rules from federation_config.json.
     */
    private void loadFederationConfig() {
        JSONParser parser = new JSONParser();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("federation_config.json")) {
            if (is == null) {
                System.err.println("❌ CRITICAL: federation_config.json not found in resources.");
                componentRoles = new HashMap<>();
                federationRules = new JSONArray();
                return;
            }
            
            JSONObject config = (JSONObject) parser.parse(new InputStreamReader(is));
            componentRoles = (java.util.Map<String, String>) config.get("component_roles");
            federationRules = (JSONArray) config.get("federation_rules");

            System.out.println("✅ Federation configuration loaded successfully.");

        } catch (IOException | ParseException e) {
            System.err.println("❌ Error loading or parsing federation_config.json: " + e.getMessage());
            e.printStackTrace();
            componentRoles = new HashMap<>();
            federationRules = new JSONArray();
        }
    }
    
    /**
     * Evaluate policy for agent communication
     * 
     * @param senderName Agent name sending the message
     * @param senderOrg Organization of sender
     * @param senderRole Role of sender
     * @param senderTrustScore Trust score of sender
     * @param senderStatus Status of sender (active/blocked)
     * @param receiverName Agent name receiving the message
     * @param receiverOrg Organization of receiver
     * @param receiverStatus Status of receiver (active/blocked)
     * @param action Action being performed (e.g., "send")
     * @return PolicyDecision with allow/deny and reason
     */
    public PolicyDecision evaluateCommunicationPolicy(
            String senderName, String senderOrg, String senderRole, double senderTrustScore, String senderStatus,
            String receiverName, String receiverOrg, String receiverRole, double receiverTrustScore, String receiverStatus, String action) {
        
        try {
            // Build OPA input JSON
            JSONObject input = new JSONObject();
            input.put("action", action);
            
            JSONObject sender = new JSONObject();
            sender.put("name", senderName);
            sender.put("org", senderOrg);
            sender.put("role", senderRole);
            sender.put("trustScore", senderTrustScore);
            sender.put("status", senderStatus);
            input.put("sender", sender);
            
            JSONObject receiver = new JSONObject();
            receiver.put("name", receiverName);
            receiver.put("org", receiverOrg);
            receiver.put("role", receiverRole);
            receiver.put("trustScore", receiverTrustScore);
            receiver.put("status", receiverStatus);
            input.put("receiver", receiver);
            
            JSONObject requestBody = new JSONObject();
            requestBody.put("input", input);
            
            // Send request to OPA
            RequestBody body = RequestBody.create( // This is an okhttp3.RequestBody
                requestBody.toString(),
                MediaType.parse("application/json")
            );
            
            Request request = new Request.Builder()
                    .url(opaUrl)
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("┌─ OPA POLICY EVALUATION ──────────────────────────");
                    System.err.println("│  ⚠️ SERVICE ERROR - HTTP " + response.code());
                    System.err.println("│  From: " + senderName + " (" + senderOrg + ")");
                    System.err.println("│  To:   " + receiverName + " (" + receiverOrg + ")");
                    System.out.println("│  Time:        " + Instant.now().toString());
                    System.err.println("└──────────────────────────────────────────────────");
                    return new PolicyDecision(false, "OPA service error", null);
                }
                
                String responseBody = response.body().string();
                org.json.JSONObject jsonResponse = new org.json.JSONObject(responseBody);
                
                boolean allowed = jsonResponse.optBoolean("result", false); // Using org.json.JSONObject here
                
                String reason = allowed ? 
                    "OPA policy allows communication" : 
                    "OPA policy denies communication";


                // Print detailed OPA policy evaluation box
                System.out.println("┌─ OPA POLICY EVALUATION ──────────────────────────");
                System.out.println("│  " + (allowed ? "✅ ALLOWED" : "❌ DENIED"));
                System.out.println("│  --- SOURCE ---");
                System.out.println("│  Time:        " + Instant.now());
                System.out.println("│  From:        " + senderName + " (" + senderOrg + ")");
                System.out.println("│  To:          " + receiverName + " (" + receiverOrg + ")");
                System.out.println("│  Role:        " + senderRole);
                System.out.println("│  Trust Score: " + senderTrustScore);
                System.out.println("│  Source FFA:  " + FederationHelper.getAgentFFA(senderName));
                System.out.println("│  --- TARGET ---"); 
                System.out.println("│  Target FFA:  " + FederationHelper.getAgentFFA(receiverName));
                
                // Determine and add federation type
                String federationType = determineFederationType(FederationHelper.getAgentFFA(senderName), FederationHelper.getAgentFFA(receiverName));
                System.out.println("│  Federation:  " + federationType);

                System.out.println("│  Reason: " + reason);
                System.out.println("└──────────────────────────────────────────────────");
                
                return new PolicyDecision(allowed, reason, requestBody.toString());
            }
            
        } catch (IOException e) {
            System.err.println("┌─ OPA POLICY EVALUATION ──────────────────────────");
            System.err.println("│  ⚠️ EVALUATION FAILED - " + e.getMessage());
            System.err.println("│  Fail-safe: DENY");
            System.err.println("└──────────────────────────────────────────────────");
            return new PolicyDecision(false, "OPA evaluation failed: " + e.getMessage(), null);
        } catch (Exception e) {
            System.err.println("┌─ OPA POLICY EVALUATION ──────────────────────────");
            System.err.println("│  ⚠️ UNEXPECTED ERROR - " + e.getMessage());
            System.err.println("│  Fail-safe: DENY");
            System.err.println("└──────────────────────────────────────────────────");
            return new PolicyDecision(false, "Unexpected error: " + e.getMessage(), null);
        }
    }

    /**
     * Determines the type of federation (Vertical or Horizontal) based on agent FFAs.
     * @param sourceFFA The Federation Fractal Address of the source agent.
     * @param targetFFA The Federation Fractal Address of the target agent.
     * @return A string indicating "Vertical", "Horizontal", or "Unknown".
     */
    private String determineFederationType(String sourceFFA, String targetFFA) {
        if (sourceFFA == null || targetFFA == null || sourceFFA.equals("NONE") || targetFFA.equals("NONE")) {
            return "Unknown (Missing FFA)";
        }

        String sourceComponent = parseComponentFromFFA(sourceFFA);
        String targetComponent = parseComponentFromFFA(targetFFA);

        if (sourceComponent == null || targetComponent == null) {
            return "Unknown (Parse Error)";
        }

        // If components are the same (e.g., Robot -> Robot), it's Horizontal
        if (sourceComponent.equals(targetComponent)) {
            return "Horizontal (Peer-to-Peer)";
        }

        // Use the dynamically loaded configuration
        String sourceRole = componentRoles.getOrDefault(sourceComponent, "unknown");
        String targetRole = componentRoles.getOrDefault(targetComponent, "unknown");

        // Iterate through the rules from the JSON file
        for (Object ruleObj : federationRules) {
            JSONObject rule = (JSONObject) ruleObj;
            String ruleSource = (String) rule.get("source");
            String ruleTarget = (String) rule.get("target");

            if (ruleSource.equals(sourceRole) && ruleTarget.equals(targetRole)) {
                return (String) rule.get("type");
            }
        }

        return "Unknown";
    }

    private String parseComponentFromFFA(String ffa) {
        try {
            // Format: EU.Manufacturing...System.Component#ID::Capability@QoS
            String[] parts = ffa.split("::")[0].split("\\.");
            String componentAndId = parts[parts.length - 1];
            return componentAndId.split("#")[0];
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Evaluate policy for federation join request
     * 
     * @param agentName Name of agent requesting to join
     * @param agentOrg Organization of agent
     * @param agentRole Role of agent
     * @param agentTrustScore Trust score of agent
     * @param agentStatus Status of agent (active/blocked)
     * @param federationId Federation ID to join
     * @return PolicyDecision with allow/deny and reason
     */
    public PolicyDecision evaluateFederationJoinPolicy(
            String agentName, String agentOrg, String agentRole, 
            double agentTrustScore, String agentStatus, String federationId) {
        
        return evaluateCommunicationPolicy(
            agentName, agentOrg, agentRole, agentTrustScore, agentStatus,
            "FederationManager", federationId, "federation_manager", 1.0, "active", "join_federation"
        );
    }
    
    /**
     * Evaluate policy for resource allocation
     * 
     * @param agentName Name of agent requesting resource
     * @param agentOrg Organization of agent
     * @param agentRole Role of agent
     * @param agentTrustScore Trust score of agent
     * @param agentStatus Status of agent (active/blocked)
     * @param resourceType Type of resource requested
     * @return PolicyDecision with allow/deny and reason
     */
    public PolicyDecision evaluateResourceAllocationPolicy(
            String agentName, String agentOrg, String agentRole,
            double agentTrustScore, String agentStatus, String resourceType) {
        
        return evaluateCommunicationPolicy(
            agentName, agentOrg, agentRole, agentTrustScore, agentStatus,
            "ResourceManager", "main", "manager", 1.0, "active", "allocate_resource"
        );
    }
    
    /**
     * Evaluate trust score update policy.
     *
     * @param currentScore The agent's current trust score.
     * @param outcome      The outcome of the last interaction (e.g., "SUCCESS", "FAILURE").
     * @param decayFactor  The factor by which the old score decays.
     * @param learningRate The rate at which the new outcome influences the score.
     * @return The newly calculated trust score. Returns the current score on failure.
     */
    public double evaluateTrustScoreUpdate(double currentScore, String outcome, double decayFactor, double learningRate) {
        String trustOpaUrl = opaUrl.replace("/allow", "/evaluate_trust");

        try {
            JSONObject trustData = new JSONObject();
            trustData.put("current_score", currentScore);
            trustData.put("outcome", outcome);

            JSONObject trustParams = new JSONObject();
            trustParams.put("decay_factor", decayFactor);
            trustParams.put("learning_rate", learningRate);

            JSONObject input = new JSONObject();
            input.put("trust_data", trustData);
            input.put("trust_params", trustParams);

            JSONObject requestBody = new JSONObject();
            requestBody.put("input", input);

            RequestBody body = RequestBody.create(
                requestBody.toString(),
                MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(trustOpaUrl)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("OPA trust evaluation failed with HTTP code: " + response.code());
                    return currentScore; // Return original score on failure
                }

                String responseBody = response.body().string();
                
                JSONParser parser = new JSONParser();
                JSONObject jsonResponse = (JSONObject) parser.parse(responseBody);

                if (jsonResponse.containsKey("result")) {
                    Object result = jsonResponse.get("result");
                    if (result instanceof Double) {
                        return (Double) result;
                    } else if (result instanceof Long) {
                        return ((Long) result).doubleValue();
                    } else if (result instanceof String) {
                        return Double.parseDouble((String) result);
                    }
                }
                
                System.err.println("OPA trust evaluation response did not contain a valid 'result' field.");
                return currentScore;

            }
        } catch (IOException | ParseException e) {
            System.err.println("Exception during OPA trust evaluation: " + e.getMessage());
            return currentScore; // Return original score on failure
        } catch (Exception e) {
            System.err.println("Unexpected error during OPA trust evaluation: " + e.getMessage());
            return currentScore; // Return original score on failure
        }
    }

    /**
     * Check if OPA service is available
     * 
     * @return true if OPA is reachable
     */
    public boolean isAvailable() {
        try {
            Request request = new Request.Builder()
                    .url(opaUrl.replace("/v1/data/authz/allow", "/health"))
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Policy decision result
     */
    public static class PolicyDecision {
        public final boolean allowed;
        public final String reason;
        public final String policyInput;
        
        public PolicyDecision(boolean allowed, String reason, String policyInput) {
            this.allowed = allowed;
            this.reason = reason;
            this.policyInput = policyInput;
        }
        
        @Override
        public String toString() {
            return "PolicyDecision{allowed=" + allowed + ", reason='" + reason + "'}";
        }
    }
    
    /**
     * Shutdown the HTTP client
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}

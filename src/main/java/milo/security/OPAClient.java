package milo.security;

import milo.federation.FederationHelper;
import okhttp3.*;
import org.json.JSONObject;
import java.io.IOException;
import java.time.Instant;
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
            RequestBody body = RequestBody.create(
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
                JSONObject jsonResponse = new JSONObject(responseBody);
                
                boolean allowed = jsonResponse.optBoolean("result", false);
                
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

package milo.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Keycloak Identity and Access Management Client
 * 
 * Integrates with Keycloak for:
 * - Agent authentication and token validation
 * - User attribute retrieval (org, role, trustScore)
 * - Token-based authorization
 */
public class KeycloakClient {

    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    
    private final String keycloakUrl;
    private final String realm;
    private final String clientId;
    private final OkHttpClient httpClient;
    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
    
    private static final String DEFAULT_KEYCLOAK_URL = "http://localhost:8080";
    private static final String DEFAULT_REALM = "warehouse-federation";
    private static final String DEFAULT_CLIENT_ID = "warehouse-client";
    
    /**
     * Create Keycloak client with default configuration
     */
    public KeycloakClient() {
        this(DEFAULT_KEYCLOAK_URL, DEFAULT_REALM, DEFAULT_CLIENT_ID);
    }
    
    /**
     * Create Keycloak client with custom configuration
     * 
     * @param keycloakUrl Base URL of Keycloak server
     * @param realm Keycloak realm name
     * @param clientId Client ID for authentication
     */
    public KeycloakClient(String keycloakUrl, String realm, String clientId) {
        this.keycloakUrl = keycloakUrl;
        this.realm = realm;
        this.clientId = clientId;
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        
        this.jwtProcessor = createJWTProcessor();
    }
    
    /**
     * Create JWT processor for token validation
     */
    private ConfigurableJWTProcessor<SecurityContext> createJWTProcessor() {
        try {
            ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            
            // Set up JWKS (JSON Web Key Set) retrieval
            String jwksUrl = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/certs";
            JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(new URL(jwksUrl));
            
            // Configure JWT processor to use RS256 algorithm
            JWSAlgorithm expectedAlgorithm = JWSAlgorithm.RS256;
            JWSKeySelector<SecurityContext> keySelector = 
                new JWSVerificationKeySelector<>(expectedAlgorithm, keySource);
            processor.setJWSKeySelector(keySelector);
            
            return processor;
        } catch (Exception e) {
            // Silent fail - will be handled during authentication
            return null;
        }
    }
    
    /**
     * Authenticate agent and obtain access token
     * 
     * @param username Agent username
     * @param password Agent password
     * @return AuthToken with access token and user attributes
     */
    public AuthToken authenticate(String username, String password) {
        try {
            String tokenUrl = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token";
            
            RequestBody formBody = new FormBody.Builder()
                    .add("client_id", clientId)
                    .add("username", username)
                    .add("password", password)
                    .add("grant_type", "password")
                    .build();
            
            Request request = new Request.Builder()
                    .url(tokenUrl)
                    .post(formBody)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("┌─ KEYCLOAK AUTHENTICATION ────────────────────────");
                    System.err.println("│  ❌ FAILED - HTTP " + response.code());
                    System.err.println("│  Agent: " + username);
                    System.err.println("└──────────────────────────────────────────────────");
                    return null;
                }
                
                String responseBody = response.body().string();
                JSONObject json = new JSONObject(responseBody);
                
                String accessToken = json.getString("access_token");
                String refreshToken = json.optString("refresh_token", null);
                int expiresIn = json.optInt("expires_in", 300);
                
                // Parse token to extract user attributes
                UserAttributes userAttrs = parseToken(accessToken);
                
                return new AuthToken(accessToken, refreshToken, expiresIn, userAttrs);
            }
            
        } catch (IOException e) {
            System.err.println("┌─ KEYCLOAK AUTHENTICATION ────────────────────────");
            System.err.println("│  ❌ ERROR - " + e.getMessage());
            System.err.println("└──────────────────────────────────────────────────");
            return null;
        } catch (Exception e) {
            System.err.println("┌─ KEYCLOAK AUTHENTICATION ────────────────────────");
            System.err.println("│  ❌ UNEXPECTED ERROR - " + e.getMessage());
            System.err.println("└──────────────────────────────────────────────────");
            return null;
        }
    }
    
    /**
     * Validate access token and extract user attributes
     * 
     * @param accessToken JWT access token
     * @return UserAttributes if token is valid, null otherwise
     */
    public UserAttributes validateToken(String accessToken) {
        try {
            return parseToken(accessToken);
        } catch (Exception e) {
            System.err.println("[KeycloakClient] Token validation failed: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Parse JWT token and extract user attributes
     */
    private UserAttributes parseToken(String accessToken) throws Exception {
        if (jwtProcessor == null) {
            throw new IllegalStateException("JWT processor not initialized");
        }
        
        // Parse and validate JWT
        JWTClaimsSet claims = jwtProcessor.process(accessToken, null);
        
        // Extract custom attributes
        String username = claims.getSubject();
        String org = extractClaim(claims, "org", "unknown");
        String role = extractClaim(claims, "role", "worker");
        double trustScore = extractTrustScore(claims);
        String status = extractClaim(claims, "status", "active");
        
        return new UserAttributes(username, org, role, trustScore, status);
    }
    
    /**
     * Extract string claim from JWT
     */
    private String extractClaim(JWTClaimsSet claims, String claimName, String defaultValue) {
        try {
            Object claim = claims.getClaim(claimName);
            if (claim != null) {
                return claim.toString();
            }
        } catch (Exception e) {
            // Claim not found
        }
        return defaultValue;
    }
    
    /**
     * Extract trust score from JWT claims
     */
    private double extractTrustScore(JWTClaimsSet claims) {
        try {
            Object claim = claims.getClaim("trustScore");
            if (claim != null) {
                if (claim instanceof Number) {
                    return ((Number) claim).doubleValue();
                } else {
                    return Double.parseDouble(claim.toString());
                }
            }
        } catch (Exception e) {
            // Trust score not found or invalid
        }
        return 0.5; // Default trust score
    }
    
    /**
     * Refresh access token using refresh token
     * 
     * @param refreshToken Refresh token
     * @return New AuthToken or null if refresh failed
     */
    public AuthToken refreshToken(String refreshToken) {
        try {
            String tokenUrl = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token";
            
            RequestBody formBody = new FormBody.Builder()
                    .add("client_id", clientId)
                    .add("refresh_token", refreshToken)
                    .add("grant_type", "refresh_token")
                    .build();
            
            Request request = new Request.Builder()
                    .url(tokenUrl)
                    .post(formBody)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                
                String responseBody = response.body().string();
                JSONObject json = new JSONObject(responseBody);
                
                String accessToken = json.getString("access_token");
                String newRefreshToken = json.optString("refresh_token", refreshToken);
                int expiresIn = json.optInt("expires_in", 300);
                
                UserAttributes userAttrs = parseToken(accessToken);
                
                return new AuthToken(accessToken, newRefreshToken, expiresIn, userAttrs);
            }
            
        } catch (Exception e) {
            System.err.println("[KeycloakClient] Token refresh failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Updates the trust score for a user in Keycloak.
     * This requires admin privileges (e.g., a service account with 'manage-users' role).
     * @param username The username of the agent/user to update.
     * @param score The new trust score.
     * @return true if successful, false otherwise.
     */
    public boolean updateUserTrustScore(String username, double score) {
        try {
            String adminToken = getAdminAccessToken();
            if (adminToken == null) {
                System.err.println("❌ KeycloakClient: Could not get admin token to update trust score.");
                return false;
            }

            String userId = getUserId(username, adminToken);
            if (userId == null) {
                System.err.println("❌ KeycloakClient: Could not find user ID for '" + username + "'.");
                return false;
            }

            // Construct the user update payload. The attribute must be a collection.
            JSONObject attributes = new JSONObject();
            attributes.put("trust_score", new JSONArray(new String[]{String.valueOf(score)}));

            JSONObject payload = new JSONObject();
            payload.put("attributes", attributes);

            String updateUserUrl = keycloakUrl + "/admin/realms/" + realm + "/users/" + userId;

            RequestBody body = RequestBody.create(payload.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(updateUserUrl)
                    .header("Authorization", "Bearer " + adminToken)
                    .put(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    System.err.println("❌ KeycloakClient: Failed to update user attribute. Response: " + response.code() + " " + response.body().string());
                    return false;
                }
                System.out.println("✅ KeycloakClient: Successfully updated trust score for " + username + " to " + score);
                return true;
            }

        } catch (Exception e) {
            System.err.println("❌ KeycloakClient: Error updating trust score: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private String getAdminAccessToken() throws IOException {
        // Read client credentials from .env file or environment variables, with a fallback for development.
        String adminClientId = dotenv.get("KEYCLOAK_ADMIN_CLIENT_ID");
        String adminClientSecret = dotenv.get("KEYCLOAK_ADMIN_CLIENT_SECRET");

        String tokenUrl = keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token";

        RequestBody formBody = new FormBody.Builder()
                .add("client_id", adminClientId)
                .add("client_secret", adminClientSecret)
                .add("grant_type", "client_credentials")
                .build();

        Request request = new Request.Builder().url(tokenUrl).post(formBody).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get admin token: " + response.code() + " " + response.body().string());
            }
            String responseBody = response.body().string();
            JSONObject json = new JSONObject(responseBody);
            return json.getString("access_token");
        }
    }

    private String getUserId(String username, String adminToken) throws IOException {
        String userSearchUrl = keycloakUrl + "/admin/realms/" + realm + "/users?username=" + username + "&exact=true";

        Request request = new Request.Builder()
                .url(userSearchUrl)
                .header("Authorization", "Bearer " + adminToken)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get user ID: " + response.code() + " " + response.body().string());
            }
            String responseBody = response.body().string();
            JSONArray users = new JSONArray(responseBody);
            if (users.length() == 0) {
                return null; // User not found
            }
            return users.getJSONObject(0).getString("id");
        }
    }
    
    /**
     * Check if Keycloak service is available
     * 
     * @return true if Keycloak is reachable
     */
    public boolean isAvailable() {
        try {
            String healthUrl = keycloakUrl + "/realms/" + realm;
            
            Request request = new Request.Builder()
                    .url(healthUrl)
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
     * User attributes extracted from Keycloak token
     */
    public static class UserAttributes {
        public final String username;
        public final String org;
        public final String role;
        public final double trustScore;
        public final String status;
        
        public UserAttributes(String username, String org, String role, double trustScore, String status) {
            this.username = username;
            this.org = org;
            this.role = role;
            this.trustScore = trustScore;
            this.status = status;
        }
        
        @Override
        public String toString() {
            return "UserAttributes{username='" + username + "', org='" + org + 
                   "', role='" + role + "', trustScore=" + trustScore + ", status='" + status + "'}";
        }
    }
    
    /**
     * Authentication token with user attributes
     */
    public static class AuthToken {
        public final String accessToken;
        public final String refreshToken;
        public final int expiresIn;
        public final UserAttributes userAttributes;
        public final long createdAt;
        
        public AuthToken(String accessToken, String refreshToken, int expiresIn, UserAttributes userAttributes) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
            this.userAttributes = userAttributes;
            this.createdAt = System.currentTimeMillis();
        }
        
        /**
         * Check if token is expired
         */
        public boolean isExpired() {
            long elapsedSeconds = (System.currentTimeMillis() - createdAt) / 1000;
            return elapsedSeconds >= expiresIn;
        }
        
        /**
         * Check if token needs refresh (80% of lifetime)
         */
        public boolean needsRefresh() {
            long elapsedSeconds = (System.currentTimeMillis() - createdAt) / 1000;
            return elapsedSeconds >= (expiresIn * 0.8);
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

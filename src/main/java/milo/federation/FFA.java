package milo.federation;

import java.util.*;

/**
 * Federation Fractal Address (FFA) utility
 *
 * Provides generation, validation, parsing, and pattern matching for federation
 * addresses
 * used by federated agents.
 * 
 * New Format (URN-compliant RFC 8141):
 * urn:dtf:GeoOrg.Domain:levelPath:System[.Component][#Instance]::Capability[@QoS]
 */
public class FFA {
    public final String geoOrg;
    public final String domain;
    public final List<String> levelPath; // e.g., Component.Subsystem.System
    public final String system;
    public final String component; // optional
    public final String instance; // optional
    public final String capability;
    public final String qos; // optional

    // Public constructor that parses a string using fromString
    public FFA(String s) {
        FFA parsed = FFA.fromString(s);
        this.geoOrg = parsed.geoOrg;
        this.domain = parsed.domain;
        this.levelPath = parsed.levelPath;
        this.system = parsed.system;
        this.component = parsed.component;
        this.instance = parsed.instance;
        this.capability = parsed.capability;
        this.qos = parsed.qos;
    }

    private FFA(String g, String d, List<String> lp, String sys, String comp, String inst, String cap, String q) {
        this.geoOrg = g;
        this.domain = d;
        this.levelPath = Collections.unmodifiableList(lp);
        this.system = sys;
        this.component = comp;
        this.instance = inst;
        this.capability = cap;
        this.qos = q;
    }

    public static FFA fromString(String s) {
        if (s == null)
            throw new IllegalArgumentException("FFA null");

        String content = s;
        if (content.startsWith("urn:dtf:")) {
            content = content.substring(8);
        }

        String head;
        String cap = "DefaultCapability";
        String qos = null;

        String[] capSplit = content.split("::", 2);
        if (capSplit.length == 2) {
            head = capSplit[0];
            String right = capSplit[1];
            int at = right.indexOf('@');
            if (at >= 0) {
                cap = right.substring(0, at);
                qos = right.substring(at + 1);
            } else {
                cap = right;
            }
        } else {
            head = capSplit[0];
            int at = head.indexOf('@');
            if (at >= 0) {
                qos = head.substring(at + 1);
                head = head.substring(0, at);
            }
        }

        String geo, dom, sys, comp = null, inst = null;
        List<String> rem = new ArrayList<>();

        if (head.contains(":")) {
            // New URN-like format with colons: geo.dom:levels:system
            String[] colonParts = head.split(":", -1); // Keep empty strings
            if (colonParts.length >= 2) {
                String gd = colonParts[0];
                int gDot = gd.indexOf('.');
                if (gDot > 0) {
                    geo = gd.substring(0, gDot);
                    dom = gd.substring(gDot + 1);
                } else {
                    geo = gd;
                    dom = "Unknown";
                }

                if (colonParts.length >= 3) {
                    String levelsStr = colonParts[1];
                    if (!levelsStr.isEmpty()) {
                        rem.addAll(Arrays.asList(levelsStr.split("\\.")));
                    }
                }

                String last = colonParts[colonParts.length - 1]; // last part is sys/comp
                int hashIdx = last.indexOf('#');
                if (hashIdx >= 0) {
                    inst = last.substring(hashIdx + 1);
                    last = last.substring(0, hashIdx);
                }
                int dotIdx = last.indexOf('.');
                if (dotIdx >= 0) {
                    sys = last.substring(0, dotIdx);
                    comp = last.substring(dotIdx + 1);
                } else {
                    sys = last;
                }
            } else {
                geo = "Unknown";
                dom = "Unknown";
                sys = "Unknown"; // Fallback failsafe
            }
        } else {
            // Legacy dot-separated format fallback
            String[] toks = head.split("\\.");
            if (toks.length < 4)
                throw new IllegalArgumentException("FFA must include GeoOrg.Domain.<levels>.System: " + s);
            geo = toks[0];
            dom = toks[1];
            for (int i = 2; i < toks.length; i++)
                rem.add(toks[i]);

            String last = rem.get(rem.size() - 1);
            int hashIdx = last.indexOf('#');
            if (hashIdx >= 0) {
                String name = last.substring(0, hashIdx);
                inst = last.substring(hashIdx + 1);
                if (rem.size() >= 2) {
                    sys = rem.get(rem.size() - 2);
                    comp = name;
                    rem = rem.subList(0, rem.size() - 2);
                } else {
                    sys = name;
                    rem = rem.subList(0, rem.size() - 1);
                }
            } else {
                if (rem.size() >= 2) {
                    sys = rem.get(rem.size() - 2);
                    comp = rem.get(rem.size() - 1);
                    rem = rem.subList(0, rem.size() - 2);
                } else {
                    sys = last;
                    rem = rem.subList(0, rem.size() - 1);
                }
            }
        }

        return new FFA(geo, dom, rem, sys, comp, inst, cap, qos);
    }

    /** Return a canonical key for map lookups */
    public String key() {
        return toString();
    }

    /**
     * Check if this FFA matches a pattern using advanced pattern matching
     */
    public boolean matches(String pattern) {
        return FFAMatcher.matches(this.toString(), pattern);
    }

    /**
     * Get hierarchical prefix (everything before ::)
     */
    public String getHierarchicalPrefix() {
        StringBuilder sb = new StringBuilder();
        sb.append("urn:dtf:").append(geoOrg).append(".").append(domain).append(":");
        if (levelPath != null && !levelPath.isEmpty()) {
            sb.append(String.join(".", levelPath));
        }
        sb.append(":");
        sb.append(system);
        if (component != null && !component.isEmpty())
            sb.append(".").append(component);
        if (instance != null && !instance.isEmpty())
            sb.append("#").append(instance);
        return sb.toString();
    }

    /**
     * Get capability part (everything after ::)
     */
    public String getCapabilityPart() {
        StringBuilder sb = new StringBuilder();
        sb.append(capability != null ? capability : "DefaultCapability");
        if (qos != null && !qos.isEmpty())
            sb.append("@").append(qos);
        return sb.toString();
    }

    /**
     * Create a pattern to find similar FFAs at the same hierarchical level
     */
    public String createSiblingPattern() {
        StringBuilder sb = new StringBuilder();
        sb.append("urn:dtf:").append(geoOrg).append(".").append(domain).append(":");
        if (levelPath != null && !levelPath.isEmpty()) {
            sb.append(String.join(".", levelPath));
        }
        sb.append(":").append(system).append(".*");
        sb.append("::").append(capability);
        return sb.toString();
    }

    /**
     * Create a builder for constructing FFAs
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder pattern for flexible FFA construction
     */
    public static class Builder {
        private String geoOrg;
        private String domain;
        private List<String> levelPath = new ArrayList<>();
        private String system;
        private String component;
        private String instance;
        private String capability;
        private String qos;

        public Builder geoOrg(String geoOrg) {
            this.geoOrg = geoOrg;
            return this;
        }

        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        public Builder levelPath(String... levels) {
            this.levelPath = Arrays.asList(levels);
            return this;
        }

        public Builder addLevel(String level) {
            this.levelPath.add(level);
            return this;
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder component(String component) {
            this.component = component;
            return this;
        }

        public Builder instance(String instance) {
            this.instance = instance;
            return this;
        }

        public Builder capability(String capability) {
            this.capability = capability;
            return this;
        }

        public Builder qos(String qos) {
            this.qos = qos;
            return this;
        }

        public FFA build() {
            if (geoOrg == null || domain == null || system == null || capability == null) {
                throw new IllegalStateException("Required FFA components missing: geoOrg, domain, system, capability");
            }
            return new FFA(geoOrg, domain, new ArrayList<>(levelPath), system, component, instance, capability, qos);
        }

        public String buildString() {
            return build().toString();
        }
    }

    public FFA child(int childId) {
        StringBuilder sb = new StringBuilder();
        sb.append("urn:dtf:").append(geoOrg).append(".").append(domain).append(":");
        if (levelPath != null && !levelPath.isEmpty()) {
            sb.append(String.join(".", levelPath));
        }
        sb.append(":");
        sb.append(system);
        if (component != null)
            sb.append(".").append(component);
        if (instance != null)
            sb.append("#").append(instance);
        sb.append(".").append(childId);
        sb.append("::").append(capability != null ? capability : "DefaultCapability");
        if (qos != null)
            sb.append("@").append(qos);
        return FFA.fromString(sb.toString());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("urn:dtf:").append(geoOrg).append(".").append(domain).append(":");
        if (levelPath != null && !levelPath.isEmpty()) {
            sb.append(String.join(".", levelPath));
        }
        sb.append(":");
        sb.append(system);
        if (component != null && !component.isEmpty())
            sb.append(".").append(component);
        if (instance != null && !instance.isEmpty())
            sb.append("#").append(instance);
        sb.append("::").append(capability != null ? capability : "DefaultCapability");
        if (qos != null && !qos.isEmpty())
            sb.append("@").append(qos);
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        FFA other = (FFA) obj;
        return toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    /**
     * Enhanced validation method with detailed error reporting
     */
    public boolean isValid() {
        return isValid(null);
    }

    /**
     * Validation method with detailed error reporting
     */
    public boolean isValid(List<String> errors) {
        List<String> validationErrors = new ArrayList<>();

        try {
            if (geoOrg == null || geoOrg.isEmpty()) {
                validationErrors.add("Missing geographic organization");
            }
            if (domain == null || domain.isEmpty()) {
                validationErrors.add("Missing domain");
            }
            if (system == null || system.isEmpty()) {
                validationErrors.add("Missing system identifier");
            }
            if (capability == null || capability.isEmpty()) {
                validationErrors.add("Missing capability specification");
            }
            if (component != null && component.isEmpty()) {
                validationErrors.add("Component specified but empty");
            }
            if (instance != null && instance.isEmpty()) {
                validationErrors.add("Instance specified but empty");
            }
            if (qos != null && qos.isEmpty()) {
                validationErrors.add("QoS specified but empty");
            }

            // Semantic validation
            if (capability != null && !isValidCapability(capability)) {
                validationErrors.add("Invalid capability format: " + capability);
            }

            if (qos != null && !isValidQoS(qos)) {
                validationErrors.add("Invalid QoS format: " + qos);
            }

        } catch (Exception e) {
            validationErrors.add("Validation exception: " + e.getMessage());
        }

        if (errors != null) {
            errors.addAll(validationErrors);
        }

        return validationErrors.isEmpty();
    }

    /**
     * Validate capability format
     */
    private boolean isValidCapability(String capability) {
        return capability.matches("[A-Za-z][A-Za-z0-9]*");
    }

    /**
     * Validate QoS format
     */
    private boolean isValidQoS(String qos) {
        return qos.matches("[a-z-]+") || qos.matches("\\d+ms|\\d+s|high|medium|low|realtime|standard");
    }

    /**
     * Get semantic information about this FFA
     */
    public Map<String, String> getSemanticInfo() {
        Map<String, String> info = new HashMap<>();
        info.put("geographic-scope", geoOrg);
        info.put("operational-domain", domain);
        info.put("hierarchy-depth", String.valueOf(levelPath.size() + 2)); // +2 for system and component
        info.put("primary-capability", capability);
        info.put("qos-requirement", qos != null ? qos : "default");
        info.put("instance-type", instance != null ? "instantiated" : "template");
        info.put("component-level", component != null ? "component" : "system");
        return info;
    }

    /**
     * Calculate semantic similarity with another FFA
     */
    public double calculateSimilarity(FFA other) {
        if (other == null)
            return 0.0;

        double similarity = 0.0;
        int factors = 0;

        // Geographic similarity
        if (this.geoOrg.equals(other.geoOrg)) {
            similarity += 1.0;
        }
        factors++;

        // Domain similarity
        if (this.domain.equals(other.domain)) {
            similarity += 1.0;
        }
        factors++;

        // System similarity
        if (this.system.equals(other.system)) {
            similarity += 1.0;
        }
        factors++;

        // Component similarity
        if (Objects.equals(this.component, other.component)) {
            similarity += 1.0;
        }
        factors++;

        // Capability similarity
        if (this.capability.equals(other.capability)) {
            similarity += 2.0; // Weight capability higher
        }
        factors += 2;

        // Level path similarity
        double levelSimilarity = calculateLevelPathSimilarity(other.levelPath);
        similarity += levelSimilarity;
        factors++;

        return similarity / factors;
    }

    /**
     * Calculate level path similarity
     */
    private double calculateLevelPathSimilarity(List<String> otherLevelPath) {
        if (this.levelPath.isEmpty() && otherLevelPath.isEmpty()) {
            return 1.0;
        }

        int commonLevels = 0;
        int maxLevels = Math.max(this.levelPath.size(), otherLevelPath.size());

        for (int i = 0; i < Math.min(this.levelPath.size(), otherLevelPath.size()); i++) {
            if (this.levelPath.get(i).equals(otherLevelPath.get(i))) {
                commonLevels++;
            }
        }

        return maxLevels > 0 ? (double) commonLevels / maxLevels : 0.0;
    }

    /**
     * Get the hierarchical distance from another FFA
     */
    public int getHierarchicalDistance(FFA other) {
        if (other == null)
            return Integer.MAX_VALUE;

        // Must be in same geographic and domain space
        if (!this.geoOrg.equals(other.geoOrg) || !this.domain.equals(other.domain)) {
            return Integer.MAX_VALUE;
        }

        // Calculate level difference
        int thisDepth = this.levelPath.size() + (component != null ? 2 : 1);
        int otherDepth = other.levelPath.size() + (other.component != null ? 2 : 1);

        return Math.abs(thisDepth - otherDepth);
    }

    /**
     * Check if this FFA is a parent of another FFA
     */
    public boolean isParentOf(FFA other) {
        if (other == null)
            return false;

        // Must be in same geographic and domain space
        if (!this.geoOrg.equals(other.geoOrg) || !this.domain.equals(other.domain)) {
            return false;
        }

        // Check if other's hierarchy starts with this FFA's hierarchy
        String thisHierarchy = getHierarchicalPrefix();
        String otherHierarchy = other.getHierarchicalPrefix();

        return otherHierarchy.startsWith(thisHierarchy + ".") && !thisHierarchy.equals(otherHierarchy);
    }

    /**
     * Check if this FFA is a child of another FFA
     */
    public boolean isChildOf(FFA other) {
        return other != null && other.isParentOf(this);
    }

    /**
     * Get all possible parent FFAs (for hierarchy traversal)
     */
    public List<String> getParentFFAPatterns() {
        List<String> parents = new ArrayList<>();

        String prefix = "urn:dtf:" + geoOrg + "." + domain + ":";
        parents.add(prefix + "**::" + capability);

        StringBuilder levelsStr = new StringBuilder();
        for (String level : levelPath) {
            if (levelsStr.length() > 0)
                levelsStr.append(".");
            levelsStr.append(level);
            parents.add(prefix + levelsStr.toString() + ":**::" + capability);
        }

        parents.add(
                prefix + (levelsStr.length() > 0 ? levelsStr.toString() : "") + ":" + system + ".**::" + capability);

        return parents;
    }
}
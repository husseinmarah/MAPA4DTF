package milo.federation;

import java.util.regex.Pattern;

/**
 * Advanced FFA pattern matching utility with flexible wildcard support
 * 
 * Supports:
 * - Single wildcards (*) for matching any component at that level
 * - Double wildcards (**) for matching multiple levels
 * - Capability-only matching (::Capability)
 * - QoS filtering (@::qos)
 * - Hierarchical prefix matching
 */
public class FFAMatcher {
    
    /**
     * Check if an FFA matches a given pattern
     * 
     * Pattern examples:
     * - "EU/Plant7.Manufacturing.*.Line5.*::Optimization" - match any subsystem level and component
     * - "*.*.Component.Subsystem.System.**::*" - match any geo/domain, specific levels, any capability
     * - "EU/Plant7.**::CoSimulation@::standard" - match any hierarchy with specific capability and QoS
     * 
     * @param ffa The FFA string to test
     * @param pattern The pattern with wildcards
     * @return true if the FFA matches the pattern
     */
    public static boolean matches(String ffa, String pattern) {
        if (ffa == null || pattern == null) {
            return false;
        }
        
        try {
            // Split both FFA and pattern into hierarchical and capability parts
            String[] ffaParts = splitFFA(ffa);
            String[] patternParts = splitFFA(pattern);
            
            if (ffaParts.length != 2 || patternParts.length != 2) {
                return false;
            }
            
            // Match hierarchical part and capability part separately
            return matchesHierarchical(ffaParts[0], patternParts[0]) && 
                   matchesCapability(ffaParts[1], patternParts[1]);
                   
        } catch (Exception e) {
            System.err.println("FFAMatcher error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Split FFA into [hierarchical, capability] parts
     */
    private static String[] splitFFA(String ffa) {
        int capIndex = ffa.indexOf("::");
        if (capIndex == -1) {
            throw new IllegalArgumentException("Invalid FFA format - missing '::': " + ffa);
        }
        
        return new String[] {
            ffa.substring(0, capIndex),           // hierarchical part
            ffa.substring(capIndex + 2)           // capability part (without ::)
        };
    }
    
    /**
     * Match hierarchical part (before ::)
     * Supports: * for single level, ** for multiple levels
     */
    private static boolean matchesHierarchical(String hierarchy, String pattern) {
        // Handle ** wildcards first (multi-level matching)
        if (pattern.contains("**")) {
            return matchesWithDoubleStar(hierarchy, pattern);
        }
        
        // Split into components and match level by level
        String[] hierarchyParts = hierarchy.split("\\.");
        String[] patternParts = pattern.split("\\.");
        
        if (hierarchyParts.length != patternParts.length) {
            return false;
        }
        
        for (int i = 0; i < hierarchyParts.length; i++) {
            if (!matchesSingleComponent(hierarchyParts[i], patternParts[i])) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Handle ** wildcards that can match multiple hierarchy levels
     */
    private static boolean matchesWithDoubleStar(String hierarchy, String pattern) {
        // Convert ** to regex .* and * to [^.]*
        String regex = pattern
            .replace(".", "\\.")          // Escape literal dots
            .replace("**", "DOUBLE_STAR") // Temporarily replace ** to avoid interference
            .replace("*", "[^.]*")        // * matches any characters except dots
            .replace("DOUBLE_STAR", ".*"); // ** matches any characters including dots
        
        Pattern p = Pattern.compile("^" + regex + "$");
        return p.matcher(hierarchy).matches();
    }
    
    /**
     * Match a single component (handles # instance suffixes)
     */
    private static boolean matchesSingleComponent(String component, String pattern) {
        if ("*".equals(pattern)) {
            return true;
        }
        
        // Handle instance patterns like Component#* or Component#1
        if (pattern.contains("#")) {
            String[] patternParts = pattern.split("#", 2);
            String[] componentParts = component.split("#", 2);
            
            // Component part must match
            if (!matchesSingleComponent(componentParts[0], patternParts[0])) {
                return false;
            }
            
            // Instance part matching
            if (componentParts.length == 1) {
                return false; // No instance in component but expected in pattern
            }
            
            return "*".equals(patternParts[1]) || patternParts[1].equals(componentParts[1]);
        }
        
        return component.equals(pattern);
    }
    
    /**
     * Match capability part (after ::)
     * Supports: capability@qos, capability@*, *@qos, *@*, *
     */
    private static boolean matchesCapability(String capability, String pattern) {
        if ("*".equals(pattern)) {
            return true;
        }
        
        // Split capability and QoS parts
        String[] capParts = capability.split("@", 2);
        String[] patternParts = pattern.split("@", 2);
        
        // Match capability part
        String capName = capParts[0];
        String patternCap = patternParts[0];
        
        if (!"*".equals(patternCap) && !capName.equals(patternCap)) {
            return false;
        }
        
        // If no QoS specified in pattern, don't check QoS
        if (patternParts.length == 1) {
            return true;
        }
        
        // Match QoS part
        String qos = capParts.length > 1 ? capParts[1] : "";
        String patternQos = patternParts[1];
        
        return "*".equals(patternQos) || qos.equals(patternQos);
    }
    
    /**
     * Create a pattern that matches any instance of a specific capability
     */
    public static String createCapabilityPattern(String geoOrg, String domain, String capability) {
        return geoOrg + "." + domain + ".**::" + capability;
    }
    
    /**
     * Create a pattern for finding specific system components
     */
    public static String createSystemPattern(String geoOrg, String domain, String system, String capability) {
        return geoOrg + "." + domain + ".*." + system + ".*::" + capability;
    }
    
    /**
     * Test method for pattern matching
     */
    public static void main(String[] args) {
        // Test cases
        String ffa1 = "EU/Plant7.Manufacturing.Component.Subsystem.System.Line5.AssemblyA#1::CoSimulation@::standard";
        String ffa2 = "EU/Plant7.Manufacturing.Component.Subsystem.System.Line5#2::Telemetry@::low-latency";
        
        String pattern1 = "EU/Plant7.Manufacturing.Component.Subsystem.System.Line5.*::CoSimulation";
        String pattern2 = "*.*.Component.Subsystem.System.**::*";
        String pattern3 = "EU/Plant7.**::CoSimulation@::standard";
        
        System.out.println("Testing FFA matching:");
        System.out.println(ffa1 + " matches " + pattern1 + ": " + matches(ffa1, pattern1));
        System.out.println(ffa1 + " matches " + pattern2 + ": " + matches(ffa1, pattern2));
        System.out.println(ffa1 + " matches " + pattern3 + ": " + matches(ffa1, pattern3));
        System.out.println(ffa2 + " matches " + pattern1 + ": " + matches(ffa2, pattern1));
    }
}

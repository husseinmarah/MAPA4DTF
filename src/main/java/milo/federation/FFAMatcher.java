package milo.federation;

import java.util.regex.Pattern;

/**
 * Advanced FFA pattern matching utility with flexible wildcard support
 * 
 * New syntax matching logic handles: `urn:dtf:g.d:l1:s.c**::Capability`
 * Under the hood, normalizes this into backwards compatible checking logic.
 */
public class FFAMatcher {

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

    private static String[] splitFFA(String ffa) {
        int capIndex = ffa.indexOf("::");
        if (capIndex == -1) {
            capIndex = ffa.length();
            return new String[] { ffa, "DefaultCapability" };
        }

        return new String[] {
                ffa.substring(0, capIndex), // hierarchical part
                ffa.substring(capIndex + 2) // capability part (without ::)
        };
    }

    private static boolean matchesHierarchical(String hierarchy, String pattern) {
        // Strip urn:dtf: prefix and translate colons to dots for internal pattern
        // matching
        if (hierarchy.startsWith("urn:dtf:")) {
            hierarchy = hierarchy.substring(8).replace(":", ".");
        }
        if (pattern.startsWith("urn:dtf:")) {
            pattern = pattern.substring(8).replace(":", ".");
        }

        // Handle empty level paths causing double dots natively
        hierarchy = hierarchy.replace("..", ".");
        pattern = pattern.replace("..", ".");

        if (pattern.contains("**")) {
            return matchesWithDoubleStar(hierarchy, pattern);
        }

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

    private static boolean matchesWithDoubleStar(String hierarchy, String pattern) {
        // Convert ** to regex .* and * to [^.]*
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", "DOUBLE_STAR")
                .replace("*", "[^.]*")
                .replace("DOUBLE_STAR", ".*");

        Pattern p = Pattern.compile("^" + regex + "$");
        return p.matcher(hierarchy).matches();
    }

    private static boolean matchesSingleComponent(String component, String pattern) {
        if ("*".equals(pattern)) {
            return true;
        }

        if (pattern.contains("#")) {
            String[] patternParts = pattern.split("#", 2);
            String[] componentParts = component.split("#", 2);

            if (!matchesSingleComponent(componentParts[0], patternParts[0])) {
                return false;
            }

            if (componentParts.length == 1) {
                return false;
            }

            return "*".equals(patternParts[1]) || patternParts[1].equals(componentParts[1]);
        }

        return component.equals(pattern);
    }

    private static boolean matchesCapability(String capability, String pattern) {
        if ("*".equals(pattern)) {
            return true;
        }

        String[] capParts = capability.split("@", 2);
        String[] patternParts = pattern.split("@", 2);

        String capName = capParts[0];
        String patternCap = patternParts[0];

        if (!"*".equals(patternCap) && !capName.equals(patternCap)) {
            return false;
        }

        if (patternParts.length == 1) {
            return true;
        }

        String qos = capParts.length > 1 ? capParts[1] : "";
        String patternQos = patternParts[1];

        return "*".equals(patternQos) || qos.equals(patternQos);
    }

    public static String createCapabilityPattern(String geoOrg, String domain, String capability) {
        return "urn:dtf:" + geoOrg + "." + domain + ":**::" + capability;
    }

    public static String createSystemPattern(String geoOrg, String domain, String system, String capability) {
        return "urn:dtf:" + geoOrg + "." + domain + ":*:" + system + ".*::" + capability;
    }

    public static void main(String[] args) {
        String ffa1 = "urn:dtf:EU.Plant7:Manufacturing.Component.Subsystem:System.Line5.AssemblyA#1::CoSimulation@standard";
        String ffa2 = "urn:dtf:EU.Plant7:Manufacturing.Component.Subsystem:System.Line5#2::Telemetry@low-latency";

        String pattern1 = "urn:dtf:EU.Plant7:Manufacturing.Component.Subsystem:System.Line5.*::CoSimulation";
        String pattern2 = "urn:dtf:*.*:Manufacturing.Component.Subsystem:**::*";
        String pattern3 = "urn:dtf:EU.Plant7:**::CoSimulation@standard";
        String pattern4 = "urn:dtf:EU.Plant7:Manufacturing.Component.Subsystem:System.**::CoSimulation@standard";

        System.out.println("Testing URN FFA matching:");
        System.out.println(ffa1 + " matches " + pattern1 + ": " + matches(ffa1, pattern1));
        System.out.println(ffa1 + " matches " + pattern2 + ": " + matches(ffa1, pattern2));
        System.out.println(ffa1 + " matches " + pattern3 + ": " + matches(ffa1, pattern3));
        System.out.println(ffa2 + " matches " + pattern1 + ": " + matches(ffa2, pattern1));
        System.out.println(ffa1 + " matches " + pattern4 + ": " + matches(ffa1, pattern4));
    }
}

package milo.federation;

import jade.core.AID;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federation Workflow Orchestrator - Tracks federation lifecycle workflows
 * 
 * Production-Ready Features:
 * - Lightweight workflow state tracking
 * - Simple lifecycle management (INITIALIZING → ALLOCATING_FFA → REGISTERING → ACTIVE)
 * - Clean logging for debugging
 * - Thread-safe workflow storage
 * 
 * Note: This orchestrator TRACKS workflow state only. 
 * Actual execution is handled by FederationHelper.
 */
public class FederationWorkflowOrchestrator {
    
    /**
     * Simplified workflow states for production use
     */
    public enum WorkflowState {
        INITIALIZING,           // Workflow created, awaiting FFA allocation
        ALLOCATING_FFA,         // FFA allocation in progress
        REGISTERING,            // Service registration in progress
        ACTIVE,                 // Workflow complete and operational
        FAILED                  // Workflow failed
    }
    
    /**
     * Simplified workflow context for production use
     */
    public static class FederationWorkflow {
        public final String workflowId;
        public final AID initiator;
        public final WorkflowType type;
        public WorkflowState state;
        public final long startTime;
        public long lastUpdate;
        
        // FFA allocation tracking
        public String allocatedFFA;
        
        // Error tracking
        public String errorMessage;
        
        public FederationWorkflow(String id, AID initiator, WorkflowType type) {
            this.workflowId = id;
            this.initiator = initiator;
            this.type = type;
            this.state = WorkflowState.INITIALIZING;
            this.startTime = System.currentTimeMillis();
            this.lastUpdate = System.currentTimeMillis();
        }
        
        public void updateState(WorkflowState newState) {
            this.state = newState;
            this.lastUpdate = System.currentTimeMillis();
        }
        
        public boolean isStale(long timeoutMs) {
            return (System.currentTimeMillis() - lastUpdate) > timeoutMs;
        }
    }
    
    /**
     * Simplified workflow types for production use
     */
    public enum WorkflowType {
        SIMPLE_FEDERATION,          // Standard federation workflow
        HIERARCHICAL_FEDERATION     // Multi-level hierarchy workflow
    }
    
    // Active workflows (thread-safe)
    private final Map<String, FederationWorkflow> activeWorkflows = new ConcurrentHashMap<>();
    
    // Configuration
    private static final long WORKFLOW_TIMEOUT_MS = 300000; // 5 minutes
    
    /**
     * Start tracking a new federation workflow
     * Note: This only creates the workflow tracker. Actual execution is handled by FederationHelper.
     * 
     * @param initiator The agent initiating the workflow
     * @param type The workflow type
     * @return Workflow ID for tracking
     */
    public String startWorkflow(AID initiator, WorkflowType type) {
        String workflowId = UUID.randomUUID().toString().substring(0, 8);
        
        FederationWorkflow workflow = new FederationWorkflow(workflowId, initiator, type);
        activeWorkflows.put(workflowId, workflow);
        
        System.out.println("┌─ WORKFLOW STARTED ────────────────────────────────");
        System.out.println("│  ID:   " + workflowId);
        System.out.println("│  Type: " + type);
        System.out.println("│  By:   " + initiator.getLocalName());
        System.out.println("└──────────────────────────────────────────────────");
        
        return workflowId;
    }
    
    /**
     * Update workflow with allocated FFA (called by FederationHelper after successful allocation)
     * 
     * @param workflowId The workflow ID
     * @param ffa The allocated FFA
     */
    public void updateFFAAllocation(String workflowId, String ffa) {
        FederationWorkflow workflow = activeWorkflows.get(workflowId);
        if (workflow != null) {
            workflow.allocatedFFA = ffa;
            workflow.updateState(WorkflowState.ALLOCATING_FFA);
            
            long elapsed = System.currentTimeMillis() - workflow.startTime;
            System.out.println("┌─ Workflow [" + workflowId + "] State Update ─────────────");
            System.out.println("│  📍 FFA ALLOCATED");
            System.out.println("│  Address: " + ffa);
            System.out.println("│  State:   INITIALIZING → ALLOCATING_FFA");
            System.out.println("│  Elapsed: " + elapsed + "ms");
            System.out.println("└────────────────────────────────────────────────────────\n");
        } else {
            System.err.println("⚠️  Workflow not found: " + workflowId);
        }
    }
    
    /**
     * Mark workflow as active (called after successful service registration)
     * 
     * @param workflowId The workflow ID
     */
    public void activateWorkflow(String workflowId) {
        FederationWorkflow workflow = activeWorkflows.get(workflowId);
        if (workflow != null) {
            WorkflowState previousState = workflow.state;
            workflow.updateState(WorkflowState.ACTIVE);
            
            long duration = System.currentTimeMillis() - workflow.startTime;
            System.out.println("┌─ Workflow [" + workflowId + "] COMPLETED ─────────────");
            System.out.println("│  ✅ WORKFLOW ACTIVE");
            System.out.println("│  Previous: " + previousState);
            System.out.println("│  Current:  ACTIVE");
            System.out.println("│  Duration: " + duration + "ms");
            System.out.println("│  FFA:      " + (workflow.allocatedFFA != null ? workflow.allocatedFFA : "N/A"));
            System.out.println("└────────────────────────────────────────────────────────\n");
        } else {
            System.err.println("⚠️  Workflow not found: " + workflowId);
        }
    }
    
    /**
     * Mark workflow as failed
     * 
     * @param workflowId The workflow ID
     * @param errorMessage The error message
     */
    public void failWorkflow(String workflowId, String errorMessage) {
        FederationWorkflow workflow = activeWorkflows.get(workflowId);
        if (workflow != null) {
            WorkflowState previousState = workflow.state;
            workflow.updateState(WorkflowState.FAILED);
            workflow.errorMessage = errorMessage;
            
            long duration = System.currentTimeMillis() - workflow.startTime;
            System.err.println("┌─ Workflow [" + workflowId + "] FAILED ───────────────");
            System.err.println("│  ❌ WORKFLOW FAILED");
            System.err.println("│  Previous: " + previousState);
            System.err.println("│  Error:    " + errorMessage);
            System.err.println("│  Duration: " + duration + "ms");
            System.err.println("│  FFA:      " + (workflow.allocatedFFA != null ? workflow.allocatedFFA : "N/A"));
            System.err.println("└────────────────────────────────────────────────────────\n");
        } else {
            System.err.println("⚠️  Workflow not found: " + workflowId);
        }
    }
    
    /**
     * Get workflow status for monitoring
     * 
     * @param workflowId The workflow ID
     * @return The workflow object or null if not found
     */
    public FederationWorkflow getWorkflowStatus(String workflowId) {
        return activeWorkflows.get(workflowId);
    }
    
    /**
     * Get all active workflows (for monitoring/debugging)
     * 
     * @return Copy of active workflows map
     */
    public Map<String, FederationWorkflow> getAllActiveWorkflows() {
        return new HashMap<>(activeWorkflows);
    }
    
    /**
     * Cleanup stale and completed workflows
     * Should be called periodically by system maintenance
     * 
     * @return Number of workflows cleaned up
     */
    public int cleanupWorkflows() {
        int cleaned = 0;
        int active = 0, failed = 0, stale = 0;
        Iterator<Map.Entry<String, FederationWorkflow>> iterator = activeWorkflows.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<String, FederationWorkflow> entry = iterator.next();
            FederationWorkflow workflow = entry.getValue();
            
            boolean shouldClean = false;
            String reason = "";
            
            // Remove completed, failed, or stale workflows
            if (workflow.state == WorkflowState.ACTIVE) {
                shouldClean = true;
                reason = "completed";
                active++;
            } else if (workflow.state == WorkflowState.FAILED) {
                shouldClean = true;
                reason = "failed";
                failed++;
            } else if (workflow.isStale(WORKFLOW_TIMEOUT_MS)) {
                shouldClean = true;
                reason = "stale";
                stale++;
            }
            
            if (shouldClean) {
                iterator.remove();
                cleaned++;
                System.out.println("🧹 Cleaned workflow [" + workflow.workflowId + "] - " + reason);
            }
        }
        
        if (cleaned > 0) {
            System.out.println("\n📊 Cleanup Summary:");
            System.out.println("   Active:  " + active);
            System.out.println("   Failed:  " + failed);
            System.out.println("   Stale:   " + stale);
            System.out.println("   Total:   " + cleaned + "\n");
        }
        
        return cleaned;
    }
    
    /**
     * Remove a workflow from tracking
     * 
     * @param workflowId The workflow ID
     * @return true if workflow was removed, false if not found
     */
    public boolean removeWorkflow(String workflowId) {
        FederationWorkflow removed = activeWorkflows.remove(workflowId);
        if (removed != null) {
            System.out.println("🗑️  Removed workflow [" + workflowId + "] - State: " + removed.state);
            return true;
        } else {
            System.out.println("⚠️  Cannot remove - workflow not found: " + workflowId);
            return false;
        }
    }
    
    /**
     * Get simple workflow statistics for monitoring
     * 
     * @return Statistics map
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // Count workflows by state
        Map<WorkflowState, Integer> stateCount = new HashMap<>();
        for (FederationWorkflow workflow : activeWorkflows.values()) {
            stateCount.merge(workflow.state, 1, Integer::sum);
        }
        
        stats.put("total", activeWorkflows.size());
        stats.put("by-state", stateCount);
        stats.put("timeout-ms", WORKFLOW_TIMEOUT_MS);
        
        return stats;
    }
    
    /**
     * Print detailed workflow statistics to console (for debugging)
     */
    public void printStatistics() {
        Map<String, Object> stats = getStatistics();
        int total = (int) stats.get("total");
        @SuppressWarnings("unchecked")
        Map<WorkflowState, Integer> byState = (Map<WorkflowState, Integer>) stats.get("by-state");
        
        System.out.println("\n╔═════════════════════════════════════════════════════════╗");
        System.out.println("║           WORKFLOW ORCHESTRATOR STATISTICS             ║");
        System.out.println("╠═════════════════════════════════════════════════════════╣");
        System.out.println("║  Total Workflows:  " + String.format("%-33d", total) + "║");
        System.out.println("║                                                         ║");
        System.out.println("║  By State:                                              ║");
        
        for (WorkflowState state : WorkflowState.values()) {
            int count = byState.getOrDefault(state, 0);
            System.out.println("║    " + String.format("%-18s", state.name()) + 
                             String.format("%3d", count) + " workflows                    ║");
        }
        
        System.out.println("║                                                         ║");
        System.out.println("║  Timeout:          " + 
            String.format("%-33s", (WORKFLOW_TIMEOUT_MS / 1000) + "s") + "║");
        System.out.println("╚═════════════════════════════════════════════════════════╝\n");
    }
}
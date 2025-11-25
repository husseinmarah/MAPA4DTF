package authz

default allow := false

# ============================================================
# ATTRIBUTE-BASED ACCESS CONTROL (ABAC)
# Policy driven by Keycloak user attributes (status, org, role, trustScore)
# ============================================================

# --- Authorized stakeholders ---
authorized_stakeholders := {
    "Stakeholder1_RobotContainer",
    "Stakeholder2_RobotContainer",
    "Stakeholder3_ConveyorContainer"
}

# ============================================================
# BLOCKING RULES - These prevent blocked agents from operating
# NOTE: All allow rules below should check status == "active"
# ============================================================

# --- Allow: worker role from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.role == "worker"
    input.sender.status == "active"  # Check status from Keycloak
    authorized_stakeholders[input.sender.org]
    is_permitted_for_receiver(input.sender, input.receiver)
}

# --- Allow: high trust score senders from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.trustScore >= 0.8
    input.sender.status == "active"  # Check status from Keycloak
    authorized_stakeholders[input.sender.org]
    input.receiver.org == "main"
}

# --- Federation Manager has global privileges (from main container) ---
allow if {
    input.action == "send"
    input.sender.role == "federation_manager"
    input.sender.status == "active"  # Check status from Keycloak
    input.sender.org == "main"
}

# --- Federation Manager from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.role == "federation_manager"
    input.sender.status == "active"  # Check status from Keycloak
    authorized_stakeholders[input.sender.org]
}

# --- Manager role from main container ---
allow if {
    input.action == "send"
    input.sender.role == "manager"
    input.sender.status == "active"  # Check status from Keycloak
    input.sender.org == "main"
}

# --- Manager role from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.role == "manager"
    input.sender.status == "active"  # Check status from Keycloak
    authorized_stakeholders[input.sender.org]
}

# --- Helper: Check if receiver is permitted ---
is_permitted_for_receiver(sender, receiver) if {
    receiver.org == "main"
    authorized_stakeholders[sender.org]
    sender.status == "active"  # Check status from Keycloak
    receiver.status == "active"  # Check receiver status
    sender.trustScore >= 0.8
}

# --- Main container can send to authorized stakeholder containers ---
is_permitted_for_receiver(sender, receiver) if {
    sender.org == "main"
    authorized_stakeholders[receiver.org]
    sender.status == "active"  # Check status from Keycloak
    receiver.status == "active"  # Check receiver status
    valid_roles := {"manager", "federation_manager"}
    valid_roles[sender.role]
}

# --- Allow communication within same authorized stakeholder container ---
is_permitted_for_receiver(sender, receiver) if {
    receiver.org != "main"
    sender.org == receiver.org
    authorized_stakeholders[sender.org]
    sender.status == "active"  # Check status from Keycloak
    receiver.status == "active"  # Check receiver status
    valid_worker_roles := {"worker"}
    valid_worker_roles[sender.role]
}

# ============================================================
# ROBOT OPERATION ACCESS CONTROL
# Controls which agents can operate robots based on status, role, and trust
# ============================================================

# --- Allow robot operations for active workers with sufficient trust ---
allow if {
    input.action == "robot_operation"
    input.sender.role == "worker"
    input.sender.status == "active"  # Must be active in Keycloak
    input.sender.trustScore >= 0.5  # Minimum trust score
    authorized_stakeholders[input.sender.org]
}

# --- Allow robot operations for managers ---
allow if {
    input.action == "robot_operation"
    input.sender.role == "manager"
    input.sender.status == "active"  # Must be active in Keycloak
    authorized_stakeholders[input.sender.org]
}

# --- Allow robot operations for federation managers ---
allow if {
    input.action == "robot_operation"
    input.sender.role == "federation_manager"
    input.sender.status == "active"  # Must be active in Keycloak
}

# --- Explicitly deny robot operations for blocked agents ---
allow if {
    input.action == "robot_operation"
    input.sender.status == "blocked"
    false  # Blocked agents cannot operate robots
}

# ============================================================
# PEER-TO-PEER COORDINATION (Horizontal Federation)
# Controls which agents can coordinate directly with each other
# ============================================================

# --- Allow peer coordination between active robots in the SAME organization ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "worker"
    input.receiver.role == "worker"
    input.sender.org == input.receiver.org  # Same organization
    input.sender.status == "active"
    input.receiver.status == "active"
    authorized_stakeholders[input.sender.org]
}

# --- Allow peer coordination between active robots in DIFFERENT organizations if both authorized ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "worker"
    input.receiver.role == "worker"
    input.sender.org != input.receiver.org  # Different organizations
    input.sender.status == "active"
    input.receiver.status == "active"
    input.sender.trustScore >= 0.85  # Higher trust required for cross-org
    input.receiver.trustScore >= 0.85
    authorized_stakeholders[input.sender.org]
    authorized_stakeholders[input.receiver.org]
}

# --- Managers can coordinate with any active worker in authorized stakeholders ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "manager"
    input.receiver.role == "worker"
    input.sender.status == "active"
    input.receiver.status == "active"
    authorized_stakeholders[input.receiver.org]
}

# --- Federation managers can coordinate with anyone ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "federation_manager"
    input.sender.status == "active"
    input.receiver.status == "active"
}

# ============================================================
# ROBOT ↔ CONVEYOR HORIZONTAL FEDERATION
# Controls bidirectional peer coordination between RobotAgents and ConveyorAgents
# ============================================================

# --- ROBOT → CONVEYOR: Access for checking production status ---
allow if {
    input.action == "conveyor_access"
    input.sender.role == "worker"
    input.sender.status == "active"
    input.sender.trustScore >= 0.8
    authorized_stakeholders[input.sender.org]
}

# --- ROBOT → CONVEYOR: Managers can access any conveyor ---
allow if {
    input.action == "conveyor_access"
    input.sender.role == "manager"
    input.sender.status == "active"
    authorized_stakeholders[input.sender.org]
}

# --- ROBOT → CONVEYOR: Federation managers can access any conveyor ---
allow if {
    input.action == "conveyor_access"
    input.sender.role == "federation_manager"
    input.sender.status == "active"
}

# --- CONVEYOR → ROBOT: Push notifications for product availability ---
allow if {
    input.action == "peer_coordination"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.role == "worker"  # Robots are workers
    input.sender.status == "active"
    input.receiver.status == "active"
    input.sender.trustScore >= 0.8
    authorized_stakeholders[input.sender.org]
    authorized_stakeholders[input.receiver.org]
}

# --- ROBOT → CONVEYOR: Acknowledgements and task acceptance ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "worker"  # Robots are workers
    input.receiver.org == "Stakeholder3_ConveyorContainer"
    input.sender.status == "active"
    input.receiver.status == "active"
    input.sender.trustScore >= 0.8
    authorized_stakeholders[input.sender.org]
    authorized_stakeholders[input.receiver.org]
}

# --- CONVEYOR → MANAGER: Conveyors can send status updates to managers ---
allow if {
    input.action == "send"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.role == "manager"
    input.sender.status == "active"
    input.receiver.status == "active"
    authorized_stakeholders[input.sender.org]
}

# --- CONVEYOR → FEDERATION MANAGER: Conveyors can communicate with federation managers ---
allow if {
    input.action == "send"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.role == "federation_manager"
    input.sender.status == "active"
    input.receiver.status == "active"
}

# ============================================================
# DYNAMIC TRUST SCORE EVALUATION
# ============================================================

default evaluate_trust = null

# --- Main evaluation when outcome is known ---
evaluate_trust = new_score if {
    trust := input.trust_data
    params := input.trust_params
    is_known_outcome(trust.outcome)

    current := trust.current_score
    outcome_val := trust_outcome_value(trust.outcome)

    decay_factor := params.decay_factor
    learning_rate := params.learning_rate

    raw := (current * decay_factor) + (outcome_val * learning_rate)

    new_score := clamp01(raw)
}

# --- If unknown outcome, return current score ---
evaluate_trust = current_score if {
    not is_known_outcome(input.trust_data.outcome)
    current_score := input.trust_data.current_score
}

# ============================================================
# Helper functions
# ============================================================

# Outcome numeric values
trust_outcome_value("SUCCESS") = 1.0
trust_outcome_value("FAILURE") = 0.0

# Recognized outcomes
is_known_outcome("SUCCESS")
is_known_outcome("FAILURE")

# Clamp a number to range [0,1]
clamp01(x) = y if {
    y := x
    x >= 0
    x <= 1
}

clamp01(x) = 0.0 if {
    x < 0
}

clamp01(x) = 1.0 if {
    x > 1
}

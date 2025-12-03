package authz

# --- Authorized stakeholders ---
authorized_stakeholders := {
    "Stakeholder1_RobotContainer",
    "Stakeholder2_RobotContainer",
    "Stakeholder3_ConveyorContainer"
}

# ============================================================
# STAKEHOLDER2 ROBOT CONTAINER POLICY
# Policies specific to Stakeholder2_RobotContainer (RobotAgent3, RobotAgent4)
# Note: RobotAgent3 has status "blocked" and is subject to blocking rules
# ============================================================

# --- Allow: worker role from Stakeholder2 ---
allow if {
    input.action == "send"
    input.sender.role == "worker"
    input.sender.status == "active"
    input.sender.org == "Stakeholder2_RobotContainer"
    is_permitted_for_receiver_s2(input.sender, input.receiver)
}

# --- Allow: high trust score senders from Stakeholder2 ---
allow if {
    input.action == "send"
    input.sender.trustScore >= 0.8
    input.sender.status == "active"
    input.sender.org == "Stakeholder2_RobotContainer"
    input.receiver.org == "main"
}

# --- Robot operations for active workers with sufficient trust ---
allow if {
    input.action == "robot_operation"
    input.sender.role == "worker"
    input.sender.status == "active"
    input.sender.trustScore >= 0.5
    input.sender.org == "Stakeholder2_RobotContainer"
}

# --- EXPLICITLY DENY: robot operations for blocked agents ---
# Note: RobotAgent3 has status "blocked" and cannot operate
allow if {
    input.action == "robot_operation"
    input.sender.status == "blocked"
    false  # Blocked agents cannot operate robots
}

# --- Peer coordination between active robots in Stakeholder2 ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "worker"
    input.receiver.role == "worker"
    input.sender.org == "Stakeholder2_RobotContainer"
    input.receiver.org == "Stakeholder2_RobotContainer"
    input.sender.status == "active"
    input.receiver.status == "active"
}

# --- Cross-org peer coordination with high trust requirement ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "worker"
    input.receiver.role == "worker"
    input.sender.org == "Stakeholder2_RobotContainer"
    input.receiver.org != "Stakeholder2_RobotContainer"
    input.receiver.org != "main"
    input.sender.status == "active"
    input.receiver.status == "active"
    input.sender.trustScore >= 0.85
    input.receiver.trustScore >= 0.85
    authorized_stakeholders[input.receiver.org]
}

# --- Stakeholder2 workers can access conveyors with sufficient trust ---
allow if {
    input.action == "conveyor_access"
    input.sender.role == "worker"
    input.sender.status == "active"
    input.sender.trustScore >= 0.8
    input.sender.org == "Stakeholder2_RobotContainer"
}

# --- Stakeholder2 robots acknowledge conveyor tasks ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "worker"
    input.sender.org == "Stakeholder2_RobotContainer"
    input.receiver.org == "Stakeholder3_ConveyorContainer"
    input.sender.status == "active"
    input.receiver.status == "active"
    input.sender.trustScore >= 0.8
}

# ============================================================
# Helper: Check if receiver is permitted for Stakeholder2
# ============================================================

is_permitted_for_receiver_s2(sender, receiver) if {
    receiver.org == "main"
    sender.status == "active"
    receiver.status == "active"
    sender.trustScore >= 0.8
}

is_permitted_for_receiver_s2(sender, receiver) if {
    receiver.org == "Stakeholder2_RobotContainer"
    sender.status == "active"
    receiver.status == "active"
    sender.trustScore >= 0.8
}


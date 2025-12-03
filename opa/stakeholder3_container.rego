package authz

# --- Authorized stakeholders ---
authorized_stakeholders := {
    "Stakeholder1_RobotContainer",
    "Stakeholder2_RobotContainer",
    "Stakeholder3_ConveyorContainer"
}

# ============================================================
# STAKEHOLDER3 CONVEYOR CONTAINER POLICY
# Policies specific to Stakeholder3_ConveyorContainer (ConveyorAgent1, ConveyorAgent2)
# ============================================================

# --- Allow: worker role from Stakeholder3 (Conveyors) ---
allow if {
    input.action == "send"
    input.sender.role == "worker"
    input.sender.status == "active"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    is_permitted_for_receiver_s3(input.sender, input.receiver)
}

# --- Allow: high trust score senders from Stakeholder3 ---
allow if {
    input.action == "send"
    input.sender.trustScore >= 0.8
    input.sender.status == "active"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.org == "main"
}

# --- Robot operations for active workers ---
allow if {
    input.action == "robot_operation"
    input.sender.role == "worker"
    input.sender.status == "active"
    input.sender.trustScore >= 0.5
    input.sender.org == "Stakeholder3_ConveyorContainer"
}

# --- Peer coordination between active conveyors in Stakeholder3 ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "worker"
    input.receiver.role == "worker"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.org == "Stakeholder3_ConveyorContainer"
    input.sender.status == "active"
    input.receiver.status == "active"
}

# --- CONVEYOR → ROBOT: Push notifications for product availability ---
allow if {
    input.action == "peer_coordination"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.role == "worker"  # Robots are workers
    input.sender.status == "active"
    input.receiver.status == "active"
    input.sender.trustScore >= 0.8
    authorized_stakeholders[input.receiver.org]
}

# --- CONVEYOR → MANAGER: Send status updates to managers ---
allow if {
    input.action == "send"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.role == "manager"
    input.sender.status == "active"
    input.receiver.status == "active"
}

# --- CONVEYOR → FEDERATION MANAGER: Communicate with federation managers ---
allow if {
    input.action == "send"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.role == "federation_manager"
    input.sender.status == "active"
    input.receiver.status == "active"
}

# --- Conveyor can be accessed by authorized robots ---
allow if {
    input.action == "conveyor_access"
    input.receiver.org == "Stakeholder3_ConveyorContainer"
    input.receiver.status == "active"
    input.sender.trustScore >= 0.8
    authorized_stakeholders[input.sender.org]
}

# --- CONVEYOR SELF-AUTHORIZATION: Allow conveyor to check its own authorization status ---
# This allows ConveyorAgents to query whether they can operate (conveyor_access action)
allow if {
    input.action == "conveyor_access"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.sender.role == "worker"
    input.sender.status == "active"
    input.sender.trustScore >= 0.8
}

# --- Stakeholder3 conveyors acknowledge robot task completions ---
allow if {
    input.action == "peer_coordination"
    input.sender.org == "Stakeholder3_ConveyorContainer"
    input.receiver.role == "worker"  # Robots
    input.receiver.org != "Stakeholder3_ConveyorContainer"
    input.sender.status == "active"
    input.receiver.status == "active"
    input.sender.trustScore >= 0.8
    authorized_stakeholders[input.receiver.org]
}

# ============================================================
# Helper: Check if receiver is permitted for Stakeholder3
# ============================================================

is_permitted_for_receiver_s3(sender, receiver) if {
    receiver.org == "main"
    sender.status == "active"
    receiver.status == "active"
    sender.trustScore >= 0.8
}

is_permitted_for_receiver_s3(sender, receiver) if {
    receiver.org == "Stakeholder3_ConveyorContainer"
    sender.status == "active"
    receiver.status == "active"
    sender.trustScore >= 0.8
}

is_permitted_for_receiver_s3(sender, receiver) if {
    authorized_stakeholders[receiver.org]
    receiver.role == "worker"
    sender.status == "active"
    receiver.status == "active"
    sender.trustScore >= 0.8
}




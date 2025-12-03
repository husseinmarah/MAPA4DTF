package authz

# --- Authorized stakeholders ---
authorized_stakeholders := {
    "Stakeholder1_RobotContainer",
    "Stakeholder2_RobotContainer",
    "Stakeholder3_ConveyorContainer"
}

# ============================================================
# MAIN CONTAINER POLICY
# Central management policies for federation managers and production managers
# ============================================================

default allow := false

# --- Federation Manager has global privileges (from main container) ---
allow if {
    input.action == "send"
    input.sender.role == "federation_manager"
    input.sender.status == "active"
    input.sender.org == "main"
}

# --- Manager role from main container ---
allow if {
    input.action == "send"
    input.sender.role == "manager"
    input.sender.status == "active"
    input.sender.org == "main"
}

# --- Main container can send to authorized stakeholder containers ---
allow if {
    input.action == "send"
    input.sender.org == "main"
    authorized_stakeholders[input.receiver.org]
    input.sender.status == "active"
    input.receiver.status == "active"
    valid_roles := {"manager", "federation_manager"}
    valid_roles[input.sender.role]
}

# --- Robot operations for managers from main ---
allow if {
    input.action == "robot_operation"
    input.sender.role == "manager"
    input.sender.status == "active"
    input.sender.org == "main"
}

# --- Robot operations for federation managers ---
allow if {
    input.action == "robot_operation"
    input.sender.role == "federation_manager"
    input.sender.status == "active"
}

# --- Federation managers can coordinate with anyone ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "federation_manager"
    input.sender.status == "active"
    input.receiver.status == "active"
}

# --- Managers can coordinate with any active worker in authorized stakeholders ---
allow if {
    input.action == "peer_coordination"
    input.sender.role == "manager"
    input.receiver.role == "worker"
    input.sender.status == "active"
    input.receiver.status == "active"
    input.sender.org == "main"
    authorized_stakeholders[input.receiver.org]
}

# --- Main container can access any conveyor ---
allow if {
    input.action == "conveyor_access"
    input.sender.role == "manager"
    input.sender.status == "active"
    input.sender.org == "main"
}

# --- Federation managers can access any conveyor ---
allow if {
    input.action == "conveyor_access"
    input.sender.role == "federation_manager"
    input.sender.status == "active"
}


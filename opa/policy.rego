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

# --- PRIORITY RULE 1: Block agents with status="blocked" (from Keycloak) ---
# This is attribute-based - just change the status in Keycloak!
allow if {
    input.sender.status == "blocked"
    false  # Explicitly deny blocked agent
}

allow if {
    input.receiver.status == "blocked"
    false  # Also block communication TO blocked agents
}

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

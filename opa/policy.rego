package authz

default allow := false

# --- Authorized stakeholders (Stakeholder2 is BLOCKED) ---
authorized_stakeholders := {"Stakeholder1_RobotContainer", "Stakeholder3_ConveyorContainer"}

# --- Blocked stakeholders (explicit deny list) ---
blocked_stakeholders := {"Stakeholder2_RobotContainer"}

# --- PRIORITY RULE: Block all communication from blocked stakeholders ---
# This rule ensures blocked stakeholders are denied before any allow rules
allow if {
    blocked_stakeholders[input.sender.org]
    false  # Explicitly deny - this rule will never allow
}

allow if {
    blocked_stakeholders[input.receiver.org]
    false  # Also block communication TO blocked stakeholders
}

# --- Allow: worker role from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.role == "worker"
    authorized_stakeholders[input.sender.org]
    not blocked_stakeholders[input.sender.org]  # Extra safety check
    is_permitted_for_receiver(input.sender, input.receiver)
}

# --- Allow: high trust score senders from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.trustScore >= 0.8
    authorized_stakeholders[input.sender.org]
    not blocked_stakeholders[input.sender.org]
    input.receiver.org == "main"
}

# --- Federation Manager has global privileges (only from authorized orgs) ---
allow if {
    input.action == "send"
    input.sender.role == "federation_manager"
    authorized_stakeholders[input.sender.org]
    not blocked_stakeholders[input.sender.org]
}

# --- Manager role from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.role == "manager"
    authorized_stakeholders[input.sender.org]
    not blocked_stakeholders[input.sender.org]
}

# --- Helper: Check if receiver is permitted ---
is_permitted_for_receiver(sender, receiver) if {
    receiver.org == "main"
    authorized_stakeholders[sender.org]
    not blocked_stakeholders[sender.org]
    sender.trustScore >= 0.8
}

# --- Main container can send to authorized stakeholder containers ---
is_permitted_for_receiver(sender, receiver) if {
    sender.org == "main"
    authorized_stakeholders[receiver.org]
    not blocked_stakeholders[receiver.org]
    valid_roles := {"manager", "federation_manager"}
    valid_roles[sender.role]
}

# --- Allow communication within same authorized stakeholder container ---
is_permitted_for_receiver(sender, receiver) if {
    receiver.org != "main"
    sender.org == receiver.org
    authorized_stakeholders[sender.org]
    not blocked_stakeholders[sender.org]
    valid_worker_roles := {"worker"}
    valid_worker_roles[sender.role]
}

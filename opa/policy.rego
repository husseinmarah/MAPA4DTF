package authz

default allow := false

# Check 1: Is sender from authorized stakeholder?
# --- Authorized stakeholders (Stakeholder2 is BLOCKED) ---
authorized_stakeholders := {"Stakeholder1_RobotContainer", "Stakeholder3_ConveyorContainer"}

# --- Block all communication from Stakeholder2_RobotContainer ---
allow if {
    input.sender.org == "Stakeholder2_RobotContainer"
    false  # Explicitly deny
}

# Check 2: Is role "worker"?
# --- Allow: worker role from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.role == "worker"
    authorized_stakeholders[input.sender.org]
    is_permitted_for_receiver(input.sender, input.receiver)
}

# --- Allow: high trust score senders from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.trustScore >= 0.8
    authorized_stakeholders[input.sender.org]
    input.receiver.org == "main"
}

# --- Federation Manager has global privileges (only from authorized orgs) ---
allow if {
    input.action == "send"
    input.sender.role == "federation_manager"
    authorized_stakeholders[input.sender.org]
}

# --- Manager role from authorized stakeholders ---
allow if {
    input.action == "send"
    input.sender.role == "manager"
    authorized_stakeholders[input.sender.org]
}

# Check 3: Can worker communicate with main?
# --- Main container can communicate with authorized stakeholder containers only ---
is_permitted_for_receiver(sender, receiver) if {
    receiver.org == "main"
    authorized_stakeholders[sender.org]
    sender.trustScore >= 0.8
}

# --- Main container can send to authorized stakeholder containers ---
is_permitted_for_receiver(sender, receiver) if {
    sender.org == "main"
    authorized_stakeholders[receiver.org]
    valid_roles := {"manager", "federation_manager"}
    valid_roles[sender.role]
}

# --- Allow communication within same authorized stakeholder container ---
is_permitted_for_receiver(sender, receiver) if {
    receiver.org != "main"
    sender.org == receiver.org
    authorized_stakeholders[sender.org]
    valid_worker_roles := {"worker"}
    valid_worker_roles[sender.role]
}

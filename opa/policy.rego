package authz

default allow := false

# --- Allow: sender with 'trusted' role and high trust score to main container ---
allow {
    input.action == "send"
    input.sender.role == "trusted"
    is_permitted_for_receiver(input.sender, input.receiver)
}

# --- Allow: high trust score senders can communicate with main container ---
allow {
    input.action == "send"
    input.sender.trustScore >= 0.8
    input.receiver.org == "main"
}

# --- Federation Manager has global privileges ---
allow {
    input.action == "send"
    input.sender.role == "federation_manager"
}

# --- Main container can communicate with all stakeholder containers ---
is_permitted_for_receiver(sender, receiver) {
    receiver.org == "main"
    valid_stakeholder_containers := {"Stakeholder1_RobotContainer", "Stakeholder2_RobotContainer", "Stakeholder3_ConveyorContainer"}
    valid_stakeholder_containers[sender.org]
    sender.trustScore >= 0.8
}

# --- Stakeholder containers can communicate with main container ---
is_permitted_for_receiver(sender, receiver) {
    sender.org == "main"
    valid_stakeholder_containers := {"Stakeholder1_RobotContainer", "Stakeholder2_RobotContainer", "Stakeholder3_ConveyorContainer"}
    valid_stakeholder_containers[receiver.org]
    valid_roles := {"manager", "federation_manager"}
    valid_roles[sender.role]
}

# --- Allow communication within same stakeholder container ---
is_permitted_for_receiver(sender, receiver) {
    receiver.org != "main"
    sender.org == receiver.org
    sender.role == "trusted"
}

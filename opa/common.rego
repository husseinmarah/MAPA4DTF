package authz

# ============================================================
# COMMON POLICIES - Shared by all containers
# ============================================================

# --- Authorized stakeholders ---
authorized_stakeholders := {
    "Stakeholder1_RobotContainer",
    "Stakeholder2_RobotContainer",
    "Stakeholder3_ConveyorContainer"
}

# ============================================================
# DYNAMIC TRUST SCORE EVALUATION
# ============================================================

default evaluate_trust = null

# --- Main evaluation when outcome is known ---
# --- SUCCESS: Use EMA to gradually increase score ---
evaluate_trust = new_score if {
    trust := input.trust_data
    params := input.trust_params
    trust.outcome == "SUCCESS"

    current := trust.current_score
    learning_rate := params.learning_rate

    # EMA for Success: Move towards 1.0
    # New = Current * (1 - LR) + 1.0 * LR
    retention := 1.0 - learning_rate
    raw := (current * retention) + learning_rate

    new_score := clamp01(raw)
}

# --- FAILURE: Apply Decay Factor penalty ---
evaluate_trust = new_score if {
    trust := input.trust_data
    params := input.trust_params
    trust.outcome == "FAILURE"

    current := trust.current_score
    decay_factor := params.decay_factor

    # Decay for Failure: Reduce by DecayFactor
    # New = Current * (1 - DecayFactor)
    retention := 1.0 - decay_factor
    raw := current * retention

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

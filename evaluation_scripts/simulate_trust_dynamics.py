import csv
import random
import os
import time
from datetime import datetime, timezone

# Constants
DECAY_FACTOR = 0.95
LEARNING_RATE = 0.1
ITERATIONS = 50
OUTPUT_FILE = '../experiment_results/trust_dynamics_log.csv'

def evaluate_trust_score_update(current_score, outcome, decay, alpha):
    outcome_val = 1.0 if outcome == "SUCCESS" else 0.0
    # Formula from OPAClient/GovernancePerformanceBenchmark
    # newScore = alpha * outcomeVal + (1 - alpha) * currentScore
    new_score = (alpha * outcome_val) + ((1 - alpha) * current_score)
    return new_score

def log_trust_update(writer, agent_name, score, reason):
    # timestamp_iso,timestamp_ms,agent_name,trust_score,reason
    now = datetime.now(timezone.utc)
    ts_iso = now.isoformat().replace("+00:00", "Z")
    ts_ms = int(now.timestamp() * 1000)
    writer.writerow([ts_iso, ts_ms, agent_name, f"{score:.4f}", reason])

def simulate_agent(writer, agent_name, start_score, success_rate_or_mode):
    current_score = start_score
    log_trust_update(writer, agent_name, current_score, "Initial")
    
    for i in range(ITERATIONS):
        success = False
        if success_rate_or_mode == "COMPROMISED":
            # First half good, second half bad
            if i < ITERATIONS / 2:
                success = True
            else:
                success = False
        else:
            # Reliable (1.0) or Unstable (0.5)
            rate = float(success_rate_or_mode)
            success = random.random() < rate
            
        outcome = "SUCCESS" if success else "FAILURE"
        current_score = evaluate_trust_score_update(current_score, outcome, DECAY_FACTOR, LEARNING_RATE)
        
        log_trust_update(writer, agent_name, current_score, outcome)
        # Small sleep to ensure unique timestamps if needed, though ms resolution is fine
        # time.sleep(0.001) 

def main():
    # Ensure directory exists
    os.makedirs(os.path.dirname(OUTPUT_FILE), exist_ok=True)
    
    with open(OUTPUT_FILE, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(["timestamp_iso", "timestamp_ms", "agent_name", "trust_score", "reason"])
        
        print("Simulating Reliable_Agent...")
        simulate_agent(writer, "Reliable_Agent", 0.5, 1.0)
        
        print("Simulating Compromised_Agent...")
        simulate_agent(writer, "Compromised_Agent", 0.5, "COMPROMISED")
        
        print("Simulating Unstable_Agent...")
        simulate_agent(writer, "Unstable_Agent", 0.5, 0.5)
        
    print(f"Simulation complete. Data written to {OUTPUT_FILE}")

if __name__ == "__main__":
    main()

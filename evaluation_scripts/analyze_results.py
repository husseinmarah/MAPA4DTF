import pandas as pd
import matplotlib.pyplot as plt
import os

# Configuration
RESULTS_DIR = 'experiment_results'
OUTPUT_DIR = 'evaluation_plots'

def setup_directories():
    if not os.path.exists(RESULTS_DIR):
        print(f"Error: Results directory '{RESULTS_DIR}' not found.")
        return False
    
    if not os.path.exists(OUTPUT_DIR):
        os.makedirs(OUTPUT_DIR)
        print(f"Created output directory: {OUTPUT_DIR}")
    return True

def load_data(filename):
    file_path = os.path.join(RESULTS_DIR, filename)
    if os.path.exists(file_path):
        try:
            return pd.read_csv(file_path)
        except Exception as e:
            print(f"Error reading {filename}: {e}")
            return None
    return None

def set_r_style(ax):
    """Applies a 'Classic R' style to the matplotlib axes."""
    ax.set_facecolor('white')
    ax.grid(False)
    # R-style box around the plot
    for spine in ax.spines.values():
        spine.set_edgecolor('black')
        spine.set_linewidth(1.0)
    
    ax.tick_params(direction='out', length=4, width=1, colors='black', grid_color='black', grid_alpha=0.5)

def plot_keycloak_auth(ax, df):
    set_r_style(ax)
    ax.set_title('Keycloak Authentication Latency', fontsize=12, fontweight='bold')
    ax.set_xlabel('Latency (ms)', fontsize=10)
    ax.set_ylabel('Frequency', fontsize=10)
    
    if df is not None and not df.empty:
        # R-style histogram: white bars with black borders
        ax.hist(df['duration_ms'], bins=15, color='white', edgecolor='black', linewidth=1.2)
        
        # Add stats box
        stats = df['duration_ms'].describe()
        textstr = f"Mean: {stats['mean']:.2f}\nMax:  {stats['max']:.2f}"
        props = dict(boxstyle='square,pad=0.5', facecolor='white', edgecolor='black', alpha=1.0)
        ax.text(0.95, 0.95, textstr, transform=ax.transAxes, fontsize=9,
                verticalalignment='top', horizontalalignment='right', bbox=props)
    else:
        ax.text(0.5, 0.5, 'No Data', ha='center', va='center')

def plot_opa_eval(ax, df):
    set_r_style(ax)
    ax.set_title('OPA Policy Evaluation', fontsize=12, fontweight='bold')
    ax.set_ylabel('Latency (ms)', fontsize=10)
    ax.set_xlabel('Event Type', fontsize=10)
    
    if df is not None and not df.empty:
        # Group data for boxplot
        events = df['event_type'].unique()
        data = [df[df['event_type'] == e]['duration_ms'] for e in events]
        labels = [e.replace('OPA_EVAL_', '') for e in events]
        
        # R-style boxplot
        ax.boxplot(data, labels=labels, patch_artist=False, 
                   boxprops=dict(linewidth=1.2, color='black'),
                   whiskerprops=dict(linewidth=1.2, color='black'),
                   capprops=dict(linewidth=1.2, color='black'),
                   medianprops=dict(linewidth=1.2, color='black'))
    else:
        ax.text(0.5, 0.5, 'No Data', ha='center', va='center')

def plot_e2e_governance(ax, df):
    set_r_style(ax)
    ax.set_title('End-to-End Overhead', fontsize=12, fontweight='bold')
    ax.set_ylabel('Latency (ms)', fontsize=10)
    ax.set_xlabel('', fontsize=10) # No x-label needed for single box
    
    if df is not None and not df.empty:
        # R-style boxplot for single distribution
        ax.boxplot([df['duration_ms']], labels=['E2E Governance'], patch_artist=False,
                   boxprops=dict(linewidth=1.2, color='black'),
                   whiskerprops=dict(linewidth=1.2, color='black'),
                   capprops=dict(linewidth=1.2, color='black'),
                   medianprops=dict(linewidth=1.2, color='black'))
        
        stats = df['duration_ms'].describe(percentiles=[0.95])
        textstr = f"Mean: {stats['mean']:.2f}\nP95:  {stats['95%']:.2f}"
        props = dict(boxstyle='square,pad=0.5', facecolor='white', edgecolor='black', alpha=1.0)
        ax.text(0.95, 0.95, textstr, transform=ax.transAxes, fontsize=9,
                verticalalignment='top', horizontalalignment='right', bbox=props)
    else:
        ax.text(0.5, 0.5, 'No Data', ha='center', va='center')

def plot_trust_dynamics(ax, df):
    set_r_style(ax)
    ax.set_title('Trust Score Evolution', fontsize=12, fontweight='bold')
    ax.set_xlabel('Interaction Step', fontsize=10) # Changed from Time to Step for cleaner x-axis
    ax.set_ylabel('Trust Score', fontsize=10)
    ax.set_ylim(0, 1.1)
    
    # Add "Denied Zone" shading
    ax.axhspan(0, 0.5, facecolor='red', alpha=0.1, label='Blocked Zone (< 0.5)')
    
    if df is not None and not df.empty:
        # R-style line plot with distinct styles
        agents = df['agent_name'].unique()
        # Specific styles for known agents usually helps interpretation
        styles = {
            'Reliable_Agent': {'color': 'green', 'fmt': '-'},
            'Compromised_Agent': {'color': 'red', 'fmt': '--'},
            'Unstable_Agent': {'color': 'blue', 'fmt': ':'},
            'Robot_Learner': {'color': 'black', 'fmt': '-.'} # Fallback
        }
        
        for agent in agents:
            agent_data = df[df['agent_name'] == agent]
            # Use index as 'step' instead of raw timestamp for clearer comparison
            steps = range(len(agent_data))
            
            style = styles.get(agent, {'color': 'black', 'fmt': '-'})
            ax.plot(steps, agent_data['trust_score'], label=agent, 
                    linestyle=style['fmt'], color=style['color'], linewidth=1.5)
            
        ax.axhline(y=0.5, color='black', linestyle=':', linewidth=1.0)
        ax.legend(loc='lower right', frameon=True, edgecolor='black', fancybox=False, facecolor='white', fontsize=8)
    else:
        ax.text(0.5, 0.5, 'No Data', ha='center', va='center')

def generate_combined_plot():
    print("\n--- Generating Combined Plot (R-Style) ---")
    
    # Load Data
    df_keycloak = load_data('keycloak_auth_benchmark.csv')
    df_opa = load_data('opa_eval_benchmark.csv')
    df_e2e = load_data('e2e_governance_benchmark.csv')
    df_trust = load_data('trust_dynamics_log.csv')
    
    # Create Subplots
    fig, axes = plt.subplots(2, 2, figsize=(12, 10)) # Slightly more compact
    plt.subplots_adjust(hspace=0.3, wspace=0.3)
    
    # Plotting
    plot_keycloak_auth(axes[0, 0], df_keycloak)
    plot_opa_eval(axes[0, 1], df_opa)
    plot_e2e_governance(axes[1, 0], df_e2e)
    plot_trust_dynamics(axes[1, 1], df_trust)
    
    # Save
    output_path = os.path.join(OUTPUT_DIR, 'metrics_summary_plot.png')
    plt.savefig(output_path, dpi=300, bbox_inches='tight')
    print(f"Combined plot saved to: {output_path}")
    plt.close()

def main():
    if not setup_directories():
        return

    generate_combined_plot()
    
    print(f"\nAnalysis complete.")

if __name__ == "__main__":
    main()

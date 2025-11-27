import React from 'react';

const TrustScoreCard = ({ trustScores }) => {
    if (!trustScores || Object.keys(trustScores).length === 0) {
        return (
            <div className="card dashboard-card">
                <h3>Trust Scores</h3>
                <div style={{ color: 'var(--text-secondary)', fontSize: '0.85rem' }}>
                    No trust scores available.
                </div>
            </div>
        );
    }

    return (
        <div className="card dashboard-card">
            <h3>Trust Scores</h3>
            <ul className="list-group">
                {Object.entries(trustScores).map(([agent, score]) => (
                    <li key={agent} className="list-item">
                        <span style={{ fontWeight: 500 }}>{agent}</span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                            <div style={{
                                width: '8px',
                                height: '8px',
                                borderRadius: '50%',
                                backgroundColor: score >= 0.8 ? 'var(--success-color)' :
                                    score >= 0.5 ? '#f59e0b' : 'var(--danger-color)'
                            }}></div>
                            <span style={{ fontFamily: 'monospace', fontWeight: 600 }}>
                                {score.toFixed(3)}
                            </span>
                        </div>
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default TrustScoreCard;

import React, { useState } from 'react';

const TrustScoreCard = ({ trustScores, onUpdateScore }) => {
    const [editingAgent, setEditingAgent] = useState(null);
    const [editValue, setEditValue] = useState('');

    const handleEditClick = (agent, currentScore) => {
        setEditingAgent(agent);
        setEditValue(currentScore);
    };

    const handleSaveClick = (agent) => {
        const score = parseFloat(editValue);
        if (!isNaN(score) && score >= 0 && score <= 1) {
            onUpdateScore(agent, score);
            setEditingAgent(null);
        } else {
            alert('Please enter a valid score between 0 and 1');
        }
    };

    const handleCancelClick = () => {
        setEditingAgent(null);
    };

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
                            {editingAgent === agent ? (
                                <>
                                    <input
                                        type="number"
                                        step="0.01"
                                        min="0"
                                        max="1"
                                        value={editValue}
                                        onChange={(e) => setEditValue(e.target.value)}
                                        style={{ width: '60px', padding: '2px 4px', fontSize: '0.85rem' }}
                                    />
                                    <button onClick={() => handleSaveClick(agent)} style={{ padding: '2px 6px', fontSize: '0.75rem', cursor: 'pointer' }}>💾</button>
                                    <button onClick={handleCancelClick} style={{ padding: '2px 6px', fontSize: '0.75rem', cursor: 'pointer' }}>❌</button>
                                </>
                            ) : (
                                <>
                                    <div style={{
                                        width: '8px',
                                        height: '8px',
                                        borderRadius: '50%',
                                        backgroundColor: score >= 0.8 ? 'var(--success-color)' :
                                            score >= 0.5 ? '#f59e0b' : 'var(--danger-color)'
                                    }}></div>
                                    <span
                                        style={{ fontFamily: 'monospace', fontWeight: 600, cursor: 'pointer', textDecoration: 'underline', textDecorationStyle: 'dotted' }}
                                        onClick={() => handleEditClick(agent, score)}
                                        title="Click to edit"
                                    >
                                        {score.toFixed(3)}
                                    </span>
                                </>
                            )}
                        </div>
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default TrustScoreCard;

import React from 'react';
import axios from 'axios';

/**
 * Renders conveyor state and allows toggling the produced flag.
 *
 * @param {Object} props Component props.
 * @param {Array} [props.conveyors=[]] Conveyor records to render.
 */
const ConveyorList = ({ conveyors = [] }) => {

    const handleProducedChange = (id, produced) => {
        axios.post(`/api/v1/conveyors/${id}/produced`, { produced });
    };

    return (
        <div className="card">
            <h2 className="section-title">Input Conveyors</h2>
            <ul className="list-group">
                {conveyors.map(conveyor => (
                    <li key={conveyor.id} className="list-item">
                        <span style={{ fontWeight: 500 }}>Conveyor {conveyor.id}</span>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                <span style={{ fontSize: '0.875rem', color: conveyor.produced ? '#10b981' : '#64748b' }}>
                                    Produced
                                </span>
                                <input
                                    type="checkbox"
                                    checked={conveyor.produced}
                                    onChange={(e) => handleProducedChange(conveyor.id, e.target.checked)}
                                />
                            </div>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                <span style={{ fontSize: '0.875rem' }}>
                                    Enabled
                                </span>
                                <input
                                    type="checkbox"
                                    checked={conveyor.enabled}
                                    readOnly
                                />
                            </div>
                        </div>
                    </li>
                ))}
            </ul>
        </div>
    );
};

export default ConveyorList;

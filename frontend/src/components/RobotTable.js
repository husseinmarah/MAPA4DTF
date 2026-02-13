import React from 'react';
import axios from 'axios';

const RobotTable = ({ robots }) => {

    const handleStopChange = (id, stop) => {
        axios.post(`/api/v1/robots/${id}/stop`, { stop });
    };

    const handleTargetChange = (id, target) => {
        axios.post(`/api/v1/robots/${id}/target`, { target });
    };

    const handlePriorityChange = (id, priority) => {
        axios.post(`/api/v1/robots/${id}/priority`, { priority: parseInt(priority) });
    };

    return (
        <div className="card">
            <h2 className="section-title">Robots</h2>
            <div style={{ overflowX: 'auto' }}>
                <table cellPadding="5" style={{ fontSize: '0.85rem' }}>
                    <thead>
                        <tr>
                            <th>ID</th>
                            <th style={{ minWidth: '145px' }}>Location</th>
                            <th style={{ minWidth: '145px' }}>Next Location</th>
                            <th>Stop</th>
                            <th>Enabled</th>
                            <th>Battery</th>
                            <th>Carrying</th>
                            <th style={{ minWidth: '100px' }}>Product</th>
                            <th style={{ minWidth: '140px' }}>Target</th>
                            <th>Priority</th>
                        </tr>
                    </thead>
                    <tbody>
                        {robots.map(robot => (
                            <tr key={robot.id}>
                                <td>{robot.id}</td>
                                <td>{robot.location}</td>
                                <td>{robot.nextLocation}</td>
                                <td>
                                    <input
                                        type="checkbox"
                                        checked={robot.stop}
                                        onChange={(e) => handleStopChange(robot.id, e.target.checked)}
                                    />
                                </td>
                                <td>
                                    <input
                                        type="checkbox"
                                        checked={robot.enabled}
                                        readOnly
                                    />
                                </td>
                                <td>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                        <div style={{
                                            width: '10px',
                                            height: '10px',
                                            borderRadius: '50%',
                                            backgroundColor: robot.batteryLevel > 20 ? '#10b981' : '#ef4444'
                                        }}></div>
                                        {robot.batteryLevel}%
                                    </div>
                                </td>
                                <td>
                                    <input
                                        type="checkbox"
                                        checked={robot.carryingProduct}
                                        readOnly
                                    />
                                </td>
                                <td>{robot.carriedProduct || '-'}</td>
                                <td>
                                    <input
                                        type="text"
                                        defaultValue={robot.target}
                                        onBlur={(e) => handleTargetChange(robot.id, e.target.value)}
                                    />
                                </td>
                                <td>
                                    <input
                                        type="number"
                                        defaultValue={robot.priority}
                                        onBlur={(e) => handlePriorityChange(robot.id, e.target.value)}
                                    />
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
};

export default RobotTable;

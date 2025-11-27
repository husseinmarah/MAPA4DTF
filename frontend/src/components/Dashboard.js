import React from 'react';

const Dashboard = ({ robots, conveyors }) => {
    const totalRobots = robots ? robots.length : 0;
    const activeRobots = robots ? robots.filter(r => r.enabled && !r.stop).length : 0;
    const avgBattery = robots && robots.length > 0
        ? Math.round(robots.reduce((acc, r) => acc + r.batteryLevel, 0) / robots.length)
        : 0;
    const totalConveyors = conveyors ? conveyors.length : 0;
    const producedCount = conveyors ? conveyors.filter(c => c.produced).length : 0;

    return (
        <div className="dashboard-grid">
            <div className="card dashboard-card">
                <h3>Total Robots</h3>
                <div className="metric-value">{totalRobots}</div>
            </div>
            <div className="card dashboard-card">
                <h3>Active Robots</h3>
                <div className="metric-value">{activeRobots}</div>
                <div className="metric-sub">Running</div>
            </div>
            <div className="card dashboard-card">
                <h3>Avg Battery</h3>
                <div className="metric-value">{avgBattery}%</div>
                <div className="progress-bar">
                    <div className="progress-fill" style={{ width: `${avgBattery}%`, backgroundColor: avgBattery > 20 ? '#10b981' : '#ef4444' }}></div>
                </div>
            </div>
            <div className="card dashboard-card">
                <h3>Production</h3>
                <div className="metric-value">{producedCount} / {totalConveyors}</div>
                <div className="metric-sub">Conveyors Active</div>
            </div>
        </div>
    );
};

export default Dashboard;

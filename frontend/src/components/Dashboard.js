import React from 'react';

/**
 * Summary metrics for the live robot and conveyor state.
 *
 * @param {Object} props Component props.
 * @param {Array} [props.robots=[]] Current robot records.
 * @param {Array} [props.conveyors=[]] Current conveyor records.
 */
const Dashboard = ({ robots = [], conveyors = [] }) => {
    const totalRobots = robots.length;
    const activeRobots = robots.filter((robot) => robot.enabled && !robot.stop).length;
    const avgBattery = robots.length > 0
        ? Math.round(robots.reduce((accumulator, robot) => accumulator + robot.batteryLevel, 0) / robots.length)
        : 0;
    const totalConveyors = conveyors.length;
    const producedCount = conveyors.filter((conveyor) => conveyor.produced).length;

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

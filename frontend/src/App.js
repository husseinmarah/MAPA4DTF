import React, { useState, useEffect } from 'react';
import RobotTable from './components/RobotTable';
import ConveyorList from './components/ConveyorList';
import Properties from './components/Properties';
import Dashboard from './components/Dashboard';
import TrustScoreCard from './components/TrustScoreCard';
import './App.css';

function App() {
    const [data, setData] = useState(null);

    useEffect(() => {
        const socket = new WebSocket('ws://localhost:8282/ws/robots');

        socket.onopen = () => {
            console.log('WebSocket connection established');
        };

        socket.onmessage = (event) => {
            const receivedData = JSON.parse(event.data);
            setData(receivedData);
        };

        socket.onclose = () => {
            console.log('WebSocket connection closed');
        };

        return () => {
            socket.close();
        };
    }, []);

    if (!data) {
        return (
            <div className="App">
                <header className="App-header">
                    <h1>Robot Control UI</h1>
                </header>
                <main style={{ display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
                    <div>Loading system data...</div>
                </main>
            </div>
        );
    }

    return (
        <div className="App">
            <header className="App-header">
                <h1>Robot Control UI</h1>
                <div className="status-container">
                    <div className={`status-badge ${data.opcUaStatus ? 'connected' : 'disconnected'}`}>
                        <div className="status-dot"></div>
                        {data.opcUaStatus ? 'OPC-UA Connected' : 'OPC-UA Disconnected'}
                    </div>
                    <div className={`status-badge ${data.opaStatus ? 'connected' : 'disconnected'}`}>
                        <div className="status-dot"></div>
                        {data.opaStatus ? 'OPA Connected' : 'OPA Disconnected'}
                    </div>
                    <div className={`status-badge ${data.keycloakStatus ? 'connected' : 'disconnected'}`}>
                        <div className="status-dot"></div>
                        {data.keycloakStatus ? 'Keycloak Connected' : 'Keycloak Disconnected'}
                    </div>
                </div>
            </header>
            <main>
                <Dashboard robots={data.robots} conveyors={data.conveyors} />

                <div className="content-grid">
                    <div className="left-column">
                        <RobotTable robots={data.robots} />
                    </div>
                    <div className="right-column">
                        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                            <TrustScoreCard trustScores={data.trustScores} />
                            <ConveyorList conveyors={data.conveyors} />
                            <Properties properties={data.properties} />
                        </div>
                    </div>
                </div>
            </main>
        </div>
    );
}

export default App;

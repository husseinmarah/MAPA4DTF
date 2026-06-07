import React from 'react';

/**
 * Displays the raw system property payloads returned by the backend.
 *
 * @param {Object} props Component props.
 * @param {Object} [props.properties={}] Property payloads grouped by source.
 */
const Properties = ({ properties = {} }) => {
    return (
        <div className="card">
            <h2 className="section-title">System Properties</h2>
            <div className="property-group">
                <h3>Pathway Properties</h3>
                <div className="code-block">{properties.pathwayProperties || 'No data'}</div>
            </div>
            <div className="property-group">
                <h3>Idle Properties</h3>
                <div className="code-block">{properties.idleProperties || 'No data'}</div>
            </div>
            <div className="property-group">
                <h3>Output Conveyor Properties</h3>
                <div className="code-block">{properties.outputConveyorProperties || 'No data'}</div>
            </div>
        </div>
    );
};

export default Properties;

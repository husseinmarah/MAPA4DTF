# MAPA4DTF

Multi-Agent Policy-Aware for Digital Twin Federation.

## Overview

MAPA4DTF is a proof-of-concept platform for implementing multi-agent policy-aware digital twin federation. The platform uses a Java backend that leverages [JAVA Agent DEvelopment Framework](https://jade.tilab.com/) for realizing of multi-agent systems through a middle-ware that complies with the FIPA specifications. The policy-aware concept is supported via Docker-based security services that provide [Keycloak](https://www.keycloak.org/) for identity and access management, [Open Agent Policy](https://www.openpolicyagent.org/) for streamlined policy enforcement and management across the federation, and [Visual Components](https://www.visualcomponents.com/) for environment simulation. In addition, the platform provides a monitoring and control dashboard (for controlling trust scores and robot status) for a developed case study (Warehouse Multi-robot System) built with the React framework.

It includes:

- a Java backend with OPC UA, JADE agents, Keycloak integration, and OPA policy enforcement
- a React frontend for live robot, conveyor, trust, and property monitoring
- Docker-based security services for Keycloak and OPA
- scripts for Visual Components simulations

## Repository Contents

- `cmd/` - startup and restart scripts for local services
- `configs/` - Visual Components and backend configuration files
- `evaluation_scripts/` - analysis and simulation scripts
- `frontend/` - React dashboard
- `opa/` - policy files for OPA
- `src/main/java/` - Java backend, OPC UA server, agents, federation, and security logic
- `src/main/resources/` - backend resources
- `vs-simulation/` - Visual Components simulation scripts

## Proof of Concept

![Demo video](/asset/demo_video.gif)

## Quick Start

1. Start the security stack:

```powershell
.\cmd\start_docker.ps1
```

2. Start the Java application from [src/main/java/milo/opcua/server/Server.java](src/main/java/milo/opcua/server/Server.java).

3. Open the frontend at http://localhost:3000 and the backend status page at http://localhost:4840/.

## Main Components

### Java Backend

The backend is a Spring Boot application that exposes the web layer and coordinates the OPC UA server, JADE container, federation logic, and security services.

Key packages:

- [milo.opcua.server](src/main/java/milo/opcua/server)
- [milo.web](src/main/java/milo/web)
- [milo.security](src/main/java/milo/security)
- [milo.federation](src/main/java/milo/federation)
- [milo.agents](src/main/java/milo/agents)

### Security Services

- Keycloak provides identity and authentication
- OPA provides policy enforcement and decision support
- the Java security managers coordinate local fallback behavior when external services are unavailable

### OPC UA Server

The OPC UA server is started from [src/main/java/milo/opcua/server/Server.java](src/main/java/milo/opcua/server/Server.java). It:

- starts the embedded OPC UA server on port `4840`
- starts the Spring Boot web application
- starts the JADE container
- launches the frontend process

### Frontend

The frontend is a React app located in [frontend/](frontend). It consumes the backend API and websocket stream for:

- robot status
- conveyor state
- trust score management
- system properties

### Visual Components

The Visual Components simulation is located in [vs-simulation/](vs-simulation). It uses the scene and configuration files defined in [configs/](configs).

## Visual Components Workflow

Use this flow to start the simulation environment and connect it to the backend:

1. Start Visual Components.
2. Open the `Connectivity` tab. If it is not visible, enable the Connectivity add-on from Tools → Settings → Add-ons.
3. If the Python console is missing, follow the guidance from the Visual Components add-on store: https://forum.visualcomponents.com/t/addon-store-manage-your-addon-installations/5018
4. Open [vs-simulation/ConfigurationScript.py](vs-simulation/ConfigurationScript.py), copy its contents into the Visual Components Python console, and execute it.
5. Confirm that the template components are created.
6. In the `Connectivity` tab, click **Import** and select [configs/CommunicationServer.xml](configs/CommunicationServer.xml).
7. Select the imported server and click **Connect**.
8. Start the simulation playback. The backend and Visual Components scene should synchronize automatically.

## Machine Specification

 | Component | Specification |
 |---|---|
 | Processor (CPU) | 11th Gen Intel Core i7-11850H @ 2.50GHz, 2496 Mhz |
 | Number of Cores/Threads | 8 Cores / 16 Logical Processor(s) |
 | Memory (RAM) | 32 GB DDR4 @ 3200 MHz |
 | Storage | 500 GB SSD |
 | Operating System | Microsoft Windows 11 Pro |
 | Graphics Card (GPU) | Intel(R) UHD Graphics Family GFX Core 600 Mhz |
 | Network Adapter | Intel(R) Wi-Fi 6E AX210 160MHz |
 | Link Speed (Receive/Transmit) | 866/866 (Mbps) |
 | IDE Software | IntelliJ IDEA / PyCharm |
 | Java Compiler | Java OpenJDK 11 / Java OpenJDK 21 |
 | Python Interpreter | Python 3.10 |
 | Platform/Tool Used | Jade 4.5.0 |

## Versions Used

### Backend and Runtime

| Component | Version |
|---|---:|
| Java source/target | 17 |
| Maven compiler plugin | 3.8.1 |
| Spring Boot starter web | 2.7.5 |
| Spring Boot starter websocket | 2.7.5 |
| Eclipse Milo SDK client | 0.6.9 |
| Eclipse Milo SDK server | 0.6.9 |
| JADE | 4.5.0 |

### Frontend

| Component | Version |
|---|---:|
| React | 18.2.0 |
| React DOM | 18.2.0 |
| react-scripts | 5.0.1 |
| axios | 1.2.1 |
| web-vitals | 2.1.4 |

### Security and Infrastructure

| Component | Version |
|---|---:|
| Keycloak Docker image | 21.1.1 |
| OPA Docker image | 1.10.1 |

### Simulation

| Component | Version |
|---|---:|
| Visual Components | 4.10 |
| Eclipse Milo (OPC-UA) | 0.6.9 |

## Prerequisites

Before starting the application, install or verify the following:

- Docker Desktop
- Java 17 JDK
- Maven
- Node.js and npm for the React frontend
- IntelliJ IDEA or another IDE capable of running the `Server` main class

Optional but useful:

- Python 3.10 for the evaluation scripts in [evaluation_scripts/](evaluation_scripts)
- a browser for the frontend UI

## Setup Guide

### 1. Clone and open the repository

Open the project root in your IDE or terminal.

### 2. Start Docker services

Run the Docker startup script from the repository root:

```powershell
.\cmd\start_docker.ps1
```

If you only want the security services, you can run:

```powershell
.\cmd\start_security_services.ps1
```

This script:

- elevates to administrator if needed
- stops processes listening on ports `8080` and `8181` if they are already running
- restarts Docker Desktop
- shuts down WSL
- runs `docker-compose down -v`
- starts Keycloak and OPA with `docker-compose up -d`

The script also checks that the services are available.

### 3. Verify the security services

After the script completes, confirm:

- Keycloak: http://localhost:8080
- OPA: http://localhost:8181

Default Keycloak credentials from the compose file:

- username: `admin`
- password: `admin`

The imported realm is loaded from [keycloak-realm.json](keycloak-realm.json).

### 4. Start the Java application

Run the main class [src/main/java/milo/opcua/server/Server.java](src/main/java/milo/opcua/server/Server.java) from IntelliJ IDEA or your preferred Java runner.

The `main` method performs the full application bootstrap:

- starts the embedded OPC UA server on port `4840`
- connects the OPC UA client
- starts the Spring Boot web application
- starts the JADE container
- launches the React frontend

### 5. Use the application

Once the server is running, open:

- backend status page: http://localhost:4840/
- frontend UI: http://localhost:3000

The frontend shows:

- robot status and controls
- conveyor state
- trust scores
- system properties

## Runtime Flow

### Docker and Security Services

1. [cmd/start_docker.ps1](cmd/start_docker.ps1) starts the Docker-based security stack.
2. Keycloak serves identity and realm data on port `8080`.
3. OPA serves policy decisions on port `8181`.
4. The Java security layer falls back to local logic if either service is unavailable.

### Java Bootstrap

1. [Server.java](src/main/java/milo/opcua/server/Server.java) starts the OPC UA server.
2. The Spring Boot backend is started through [WebApplication.java](src/main/java/milo/web/WebApplication.java).
3. JADE agents are created through the application bootstrap.
4. The frontend is launched from the Java process via the `frontend/` directory.

### Visual Components

The Visual Components runs the simulation [Simulation_Source_Script.py](vs-simulation/Simulation_Source_Script.py). Also, it uses OPC-UA protocol that is exposed on port 

### List of Service Ports and Addresses

| Service | Port | Address |
|---|---:|---:|
| OPC UA server port | 4840 | http://localhost:4840/ |
| Keycloak port | 8080 | http://localhost:8080/ |
| OPA port | 8181 | http://localhost:8181/ |
| Frontend port | 3000 | http://localhost:3000/ |



## Evaluation and Analysis Scripts

The repository also includes:

- [evaluation_scripts/analyze_results.py](evaluation_scripts/analyze_results.py)
- [evaluation_scripts/simulate_trust_dynamics.py](evaluation_scripts/simulate_trust_dynamics.py)
- [vs-simulation/ConfigurationScript.py](vs-simulation/ConfigurationScript.py)
- [vs-simulation/Simulation_Source_Script.py](vs-simulation/Simulation_Source_Script.py)

These scripts are intended for simulation and analysis workflows.

## Repository Structure

- `cmd/` - startup and restart scripts
- `configs/` - Visual Components and simulation configuration files
- `evaluation_scripts/` - Python analysis scripts
- `frontend/` - React application
- `opa/` - policy files for OPA
- `src/main/java/` - Java backend, OPC UA server, agents, federation, and security
- `src/main/resources/` - backend resources
- `vs-simulation/` - simulation scripts

## Notes

- The project is built with Java 17, even though the development environment may also have Java 11 and Java 21 installed.
- If the frontend dependencies are missing, run `npm install` inside [frontend/](frontend).
- The application has been verified with `mvn -DskipTests compile`.

## Troubleshooting

- If Keycloak or OPA fails to start, rerun [cmd/start_docker.ps1](cmd/start_docker.ps1) or inspect `docker-compose logs -f`.
- If port `8080` or `8181` is already in use, stop the conflicting process before starting Docker.
- If the frontend does not start automatically, run `npm start` inside [frontend/](frontend).
- If the Java server fails to start, verify that Docker services are healthy and that JDK 17 is selected in the IDE.

## Contributing

- Everyone is welcome for contributing.
- Please report any issues on : [GitHub Issues](https://github.com/husseinmarah/MAPA4DTF/issues)

---

## License

This project is protected under the [MIT](LICENSE) License. For more details, refer to the [LICENSE](LICENSE.txt) file.

---

<p align="center">
   <a href="#top">Return to top</a>
</p>



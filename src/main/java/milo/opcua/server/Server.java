package milo.opcua.server;

import milo.web.WebApplication;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfigBuilder;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.identity.CompositeValidator;
import org.eclipse.milo.opcua.sdk.server.util.HostnameUtil;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.eclipse.milo.opcua.stack.server.security.ServerCertificateValidator;

import java.security.cert.X509Certificate;
import java.util.List;

import static java.util.Collections.singleton;
import static org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText.english;

/**
 * Main server launcher for the federated multi-agent system with policy
 * enforcement.
 */
public class Server {

    /**
     * Starts the OPC-UA server, Spring web application, agent container, and frontend.
     */
    public static void main(final String[] args) throws Exception {
        System.setProperty("java.awt.headless", "false");
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.out.println("🚀 Starting Federated Multi-Agent System with Policy Enforcement");

        // Start the OPC UA server and get the namespace
        CustomNamespace namespace = startOpcUaServer();

        // Wait for the server to fully initialize
        Thread.sleep(2000);

        // Create the OPC UA client
        OpcUaClient client = OpcUaClient.create("opc.tcp://localhost:" + SystemConfig.SERVER_PORT);
        client.connect().get();

        // Start the Spring Boot application
        // Add a shutdown hook to close the Spring context
        Runtime.getRuntime().addShutdownHook(new Thread(WebApplication.run(args, client, namespace)::close));

        // Start the container with configured agents
        Container.startContainer();

        // Start the frontend React application
        startFrontend();

        System.out.println("✅ Federated Multi-Agent System started successfully");
        System.out.println("📋 Available Services:");
        System.out.println("   • OPC UA Server: opc.tcp://localhost:" + SystemConfig.SERVER_PORT);
        System.out.println("   • JADE Container: Main-Container with policy enforcement");
        System.out.println("   • Federation Services: Available through FAM agent");
        System.out.println("   • Policy Management: Integrated with DF and AMS");
        System.out.println("   • Web UI: http://localhost:3000");
        System.out.println("   • React Frontend: Starting...");

        // Wait indefinitely
        Thread.sleep(Long.MAX_VALUE);
    }


    /**
     * Launches the React frontend from the local frontend directory.
     */
    private static void startFrontend() {
        Thread frontendThread = new Thread(() -> {
            try {
                String frontendPath = System.getProperty("user.dir") + "/frontend";
                
                System.out.println("🌐 Starting React Frontend...");
                System.out.println("   Frontend path: " + frontendPath);

                // Check if frontend directory exists
                java.io.File frontendDir = new java.io.File(frontendPath);
                if (!frontendDir.exists()) {
                    System.err.println("❌ Frontend directory not found: " + frontendPath);
                    return;
                }

                // Build the command based on OS
                ProcessBuilder processBuilder;
                String os = System.getProperty("os.name").toLowerCase();
                
                if (os.contains("win")) {
                    // Windows: Use npm.cmd
                    processBuilder = new ProcessBuilder("npm.cmd", "start");
                } else {
                    // Linux/Mac: Use npm
                    processBuilder = new ProcessBuilder("npm", "start");
                }

                processBuilder.directory(frontendDir);
                processBuilder.inheritIO(); // Inherit console I/O to see npm output
                
                Process process = processBuilder.start();
                System.out.println("✅ React Frontend started (PID: " + process.pid() + ")");
                
                // Wait for the process to complete
                int exitCode = process.waitFor();
                System.out.println("⚠️  React Frontend process exited with code: " + exitCode);

            } catch (Exception e) {
                System.err.println("❌ Failed to start React Frontend: " + e.getMessage());
                e.printStackTrace();
            }
        }, "Frontend-Starter");

        frontendThread.setDaemon(false);
        frontendThread.start();
    }

    /**
     * Builds and starts the embedded OPC-UA server and registers the namespace.
     *
     * The certificate validator is intentionally permissive in this local setup.
     */
    private static CustomNamespace startOpcUaServer() throws Exception {
        final OpcUaServerConfigBuilder builder = new OpcUaServerConfigBuilder();

        builder.setIdentityValidator(new CompositeValidator(
                AnonymousIdentityValidator.INSTANCE));
        final EndpointConfiguration.Builder endpointBuilder = new EndpointConfiguration.Builder();
        endpointBuilder.addTokenPolicies(
                OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS);
        endpointBuilder.setSecurityPolicy(SecurityPolicy.None);
        endpointBuilder.setBindPort(SystemConfig.SERVER_PORT);
        builder.setEndpoints(singleton(endpointBuilder.build()));
        builder.setApplicationName(english(SystemConfig.SERVER_NAME));
        builder.setApplicationUri(
                "urn:" + HostnameUtil.getHostname() + ":" + SystemConfig.SERVER_PORT + "/" + SystemConfig.SERVER_NAME);
        builder.setBuildInfo(new BuildInfo(
                "urn:example:productUri", // productUri
                "Manufacturing System", // manufacturerName
                "OPC UA Server", // productName
                "1.0.0", // softwareVersion
                "build-1.0", // buildNumber
                new DateTime(134042357451980000L) // buildDate (from your example)
        ));
        builder.setCertificateManager(new DefaultCertificateManager());

        builder.setCertificateValidator(new ServerCertificateValidator() {
            @Override
            public void validateCertificateChain(List<X509Certificate> list, String s) throws UaException {
                // Intentionally accept the certificate chain for the local dev server.
            }

            @Override
            public void validateCertificateChain(List<X509Certificate> list) throws UaException {
                // Intentionally accept the certificate chain for the local dev server.
            }
        });

        final OpcUaServer server = new OpcUaServer(builder.build());
        // register namespace and keep reference
        CustomNamespace namespace = new CustomNamespace(server);
        server.getAddressSpaceManager().register(namespace);
        // start it up
        server.startup().get();
        System.out.println("OPC-UA Server: " + server.getConfig().getBuildInfo());
        return namespace;
    }
}

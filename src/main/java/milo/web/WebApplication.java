package milo.web;

import milo.opcua.server.CustomNamespace;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
/**
 * Spring Boot entry point for the web application.
 *
 * The OPC-UA server bootstrap passes shared runtime objects into this class so
 * they can be published as Spring beans for the web layer.
 */
public class WebApplication {

    private static OpcUaClient opcUaClient;
    private static CustomNamespace customNamespace;

    /**
     * Starts the Spring application and stores the shared OPC-UA runtime objects.
     */
    public static ConfigurableApplicationContext run(String[] args, OpcUaClient client, CustomNamespace namespace) {
        opcUaClient = client;
        customNamespace = namespace;
        return SpringApplication.run(WebApplication.class, args);
    }

    /**
     * Exposes the shared OPC-UA client as a Spring bean.
     */
    @Bean
    public OpcUaClient opcUaClient() {
        return opcUaClient;
    }

    /**
     * Exposes the shared namespace as a Spring bean.
     */
    @Bean
    public CustomNamespace customNamespace() {
        return customNamespace;
    }
}

package milo.web;

import milo.opcua.server.CustomNamespace;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WebApplication {

    private static OpcUaClient opcUaClient;
    private static CustomNamespace customNamespace;

    public static ConfigurableApplicationContext run(String[] args, OpcUaClient client, CustomNamespace namespace) {
        opcUaClient = client;
        customNamespace = namespace;
        return SpringApplication.run(WebApplication.class, args);
    }

    @Bean
    public OpcUaClient opcUaClient() {
        return opcUaClient;
    }

    @Bean
    public CustomNamespace customNamespace() {
        return customNamespace;
    }
}

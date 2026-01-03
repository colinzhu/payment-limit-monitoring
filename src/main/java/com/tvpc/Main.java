package com.tvpc;

import com.tvpc.event.SettlementEvent;
import com.tvpc.event.SettlementEventCodec;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Main application entry point
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        log.info("Starting Payment Limit Monitoring System...");

        // Write PID to file for easy process management
        writePidToFile();

        // Create Vertx instance with options
        VertxOptions options = new VertxOptions()
                .setWorkerPoolSize(10)
                .setEventLoopPoolSize(5);

        Vertx vertx = Vertx.vertx(options);

        // Register message codec for SettlementEvent
        vertx.eventBus().registerDefaultCodec(SettlementEvent.class, new SettlementEventCodec());
        log.info("Registered SettlementEvent message codec");

        // Load configuration from application.yml
        JsonObject config = loadConfig();

        // Deploy HTTP Server Verticle
        HttpServerVerticle httpServerVerticle = new HttpServerVerticle();

        vertx.deployVerticle(httpServerVerticle, new io.vertx.core.DeploymentOptions()
                .setConfig(config)
                .setInstances(1))
                .onSuccess(deploymentId -> {
                    log.info("HTTP Server Verticle deployed successfully: {}", deploymentId);

                    // Add shutdown hook
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        log.info("Shutting down Payment Limit Monitoring System...");
                        vertx.close();
                    }));

                    log.info("Payment Limit Monitoring System is ready!");
                    log.info("API Endpoint: http://localhost:8081/api/settlements");
                    log.info("Health Check: http://localhost:8081/health");
                })
                .onFailure(error -> {
                    log.error("Failed to deploy HTTP Server Verticle", error);
                    vertx.close();
                });
    }

    private static JsonObject loadConfig() {
        try (InputStream is = Main.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (is == null) {
                throw new RuntimeException("application.yml not found in classpath");
            }

            // Read the YAML file
            String yaml = new String(is.readAllBytes());

            // Simple YAML parsing for our config structure
            // This is a basic parser - for production use a proper YAML library
            JsonObject config = new JsonObject();

            // Parse HTTP config
            if (yaml.contains("port: 8081")) {
                config.put("http.port", 8081);
            }

            // Parse Database config - Oracle only
            JsonObject dbConfig = new JsonObject();
            if (yaml.contains("jdbc:oracle")) {
                dbConfig.put("url", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
                dbConfig.put("driver_class", "oracle.jdbc.OracleDriver");
                dbConfig.put("user", "tvpc");
                dbConfig.put("password", "tvpc123");
                dbConfig.put("max_pool_size", 20);
            } else {
                throw new RuntimeException("application.yml must contain Oracle JDBC configuration");
            }

            config.put("database", dbConfig);
            log.info("Loaded configuration from application.yml");
            return config;

        } catch (Exception e) {
            log.error("Failed to load application.yml: {}", e.getMessage());
            throw new RuntimeException("Configuration error: application.yml required", e);
        }
    }

    /**
     * Write the current process PID to a file for easy management
     */
    private static void writePidToFile() {
        try {
            String pid = String.valueOf(ProcessHandle.current().pid());
            try (FileWriter writer = new FileWriter("app.pid")) {
                writer.write(pid);
            }
            log.info("PID written to app.pid: {}", pid);
        } catch (IOException e) {
            log.warn("Failed to write PID to file: {}", e.getMessage());
        }
    }
}

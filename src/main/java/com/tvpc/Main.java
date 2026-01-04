package com.tvpc;

import com.tvpc.adapter.in.web.HttpServerVerticle;
import com.tvpc.domain.event.SettlementEvent;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;

/**
 * Main application entry point
 */
@Slf4j
public class Main {

    public static void main(String[] args) {
        log.info("Starting Payment Limit Monitoring System...");

        writePidToFile();

        VertxOptions options = new VertxOptions()
                .setWorkerPoolSize(10)
                .setEventLoopPoolSize(5);

        Vertx vertx = Vertx.vertx(options);

        // Register message codec for SettlementEvent
        vertx.eventBus().registerDefaultCodec(SettlementEvent.class, new SettlementEventCodec());
        log.info("Registered SettlementEvent message codec");

        JsonObject config = loadConfig();

        HttpServerVerticle httpServerVerticle = new HttpServerVerticle();

        vertx.deployVerticle(httpServerVerticle, new io.vertx.core.DeploymentOptions()
                        .setConfig(config)
                        .setInstances(1))
                .onSuccess(deploymentId -> {
                    log.info("HTTP Server Verticle deployed successfully: {}", deploymentId);

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

            String yaml = new String(is.readAllBytes());

            JsonObject config = new JsonObject();

            if (yaml.contains("port: 8081")) {
                config.put("http.port", 8081);
            }

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

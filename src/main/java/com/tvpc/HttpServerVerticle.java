package com.tvpc;

import com.tvpc.event.EventPublisher;
import com.tvpc.handler.SettlementIngestionHandler;
import com.tvpc.repository.*;
import com.tvpc.repository.impl.*;
import com.tvpc.router.SettlementRouter;
import com.tvpc.service.ConfigurationService;
import com.tvpc.service.SettlementIngestionService;
import com.tvpc.service.impl.InMemoryConfigurationService;
import com.tvpc.validation.SettlementValidator;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.SqlClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP Server Verticle - handles all HTTP requests
 */
public class HttpServerVerticle extends AbstractVerticle {
    private static final Logger log = LoggerFactory.getLogger(HttpServerVerticle.class);

    private static final int DEFAULT_PORT = 8080;

    private SqlClient sqlClient;
    private SettlementIngestionService ingestionService;
    private SettlementIngestionHandler ingestionHandler;

    @Override
    public void start(Promise<Void> startPromise) {
        log.info("Starting HTTP Server Verticle...");

        // Initialize database connection
        initializeDatabase()
                .compose(v -> {
                    log.info("Database initialized successfully");
                    // Initialize services
                    initializeServices();
                    // Start HTTP server
                    return startHttpServer();
                })
                .onSuccess(v -> {
                    log.info("HTTP Server Verticle started successfully on port {}", getPort());
                    startPromise.complete();
                })
                .onFailure(error -> {
                    log.error("Failed to start HTTP Server Verticle", error);
                    startPromise.fail(error);
                });
    }

    @Override
    public void stop() {
        if (sqlClient != null) {
            sqlClient.close();
        }
        log.info("HTTP Server Verticle stopped");
    }

    private Future<Void> initializeDatabase() {
        Promise<Void> promise = Promise.promise();

        try {
            // Get database configuration from application.yml
            JsonObject dbConfig = config().getJsonObject("database");
            if (dbConfig == null) {
                promise.fail("Database configuration not found in application.yml");
                return promise.future();
            }

            log.info("Connecting to database: {}", dbConfig.getString("url"));

            // Use JsonObject configuration for JDBCPool
            JsonObject poolConfig = new JsonObject()
                    .put("url", dbConfig.getString("url"))
                    .put("user", dbConfig.getString("user"))
                    .put("password", dbConfig.getString("password"))
                    .put("driver_class", dbConfig.getString("driver_class"))
                    .put("max_pool_size", dbConfig.getInteger("max_pool_size", 10));

            sqlClient = io.vertx.jdbcclient.JDBCPool.pool(vertx, poolConfig);

            // Test connection (schema already created in Oracle)
            sqlClient.query("SELECT 1 FROM DUAL").execute()
                    .onSuccess(result -> {
                        log.info("Database connection test successful");
                        promise.complete();
                    })
                    .onFailure(error -> {
                        log.error("Database connection failed", error);
                        promise.fail(error);
                    });

        } catch (Exception e) {
            log.error("Error initializing database", e);
            promise.fail(e);
        }

        return promise.future();
    }

    private void initializeServices() {
        // Repositories
        SettlementRepository settlementRepository = new JdbcSettlementRepository(sqlClient);
        RunningTotalRepository runningTotalRepository = new JdbcRunningTotalRepository(sqlClient);
        ExchangeRateRepository exchangeRateRepository = new JdbcExchangeRateRepository(sqlClient);
        ActivityRepository activityRepository = new JdbcActivityRepository(sqlClient);

        // Event publisher
        EventPublisher eventPublisher = new EventPublisher(vertx);

        // Configuration service
        ConfigurationService configurationService = new InMemoryConfigurationService();

        // Validator
        SettlementValidator validator = new SettlementValidator();

        // Ingestion service
        ingestionService = new SettlementIngestionService(
                validator,
                settlementRepository,
                runningTotalRepository,
                activityRepository,
                eventPublisher,
                sqlClient,
                configurationService
        );

        // Handler
        ingestionHandler = new SettlementIngestionHandler(ingestionService);

        log.info("Services initialized successfully");
    }

    private Future<Void> startHttpServer() {
        Promise<Void> promise = Promise.promise();

        Router router = Router.router(vertx);

        // Global handlers
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        // Setup routes
        SettlementRouter settlementRouter = new SettlementRouter(router, ingestionHandler);
        settlementRouter.setupRoutes();

        // Default route - 404
        router.route().handler(ctx -> {
            ctx.response()
                    .setStatusCode(404)
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                            .put("status", "error")
                            .put("message", "Endpoint not found")
                            .encode()
                    );
        });

        int port = getPort();

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(server -> {
                    log.info("HTTP server listening on port {}", port);
                    promise.complete();
                })
                .onFailure(promise::fail);

        return promise.future();
    }

    private int getPort() {
        return config().getInteger("http.port", DEFAULT_PORT);
    }
}

package com.tvpc.adapter.in.web;

import com.tvpc.adapter.in.web.ingestion.SettlementIngestionHandler;
import com.tvpc.adapter.out.persistence.*;
import com.tvpc.application.port.in.SettlementIngestionUseCase;
import com.tvpc.application.port.out.*;
import com.tvpc.application.service.SettlementIngestionUseCaseImpl;
import com.tvpc.application.service.SettlementValidator;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;
import io.vertx.jdbcclient.JDBCPool;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP Server Verticle - handles all HTTP requests
 * Infrastructure component that wires up the hexagonal architecture
 */
@Slf4j
public class HttpServerVerticle extends AbstractVerticle {

    private static final int DEFAULT_PORT = 8080;

    private JDBCPool jdbcPool;
    private SettlementIngestionHandler ingestionHandler;

    @Override
    public void start(Promise<Void> startPromise) {
        log.info("Starting HTTP Server Verticle...");

        initializeDatabase()
                .compose(v -> {
                    log.info("Database initialized successfully");
                    initializeServices();
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
        if (jdbcPool != null) {
            jdbcPool.close();
        }
        log.info("HTTP Server Verticle stopped");
    }

    private Future<Void> initializeDatabase() {
        try {
            JsonObject dbConfig = config().getJsonObject("database");
            if (dbConfig == null) {
                return Future.failedFuture("Database configuration not found in application.yml");
            }

            log.info("Connecting to database: {}", dbConfig.getString("url"));

            JsonObject poolConfig = new JsonObject()
                    .put("url", dbConfig.getString("url"))
                    .put("user", dbConfig.getString("user"))
                    .put("password", dbConfig.getString("password"))
                    .put("driver_class", dbConfig.getString("driver_class"))
                    .put("max_pool_size", dbConfig.getInteger("max_pool_size", 10));

            jdbcPool = JDBCPool.pool(vertx, poolConfig);

            return jdbcPool.query("SELECT 1 FROM DUAL").execute()
                    .onSuccess(result -> log.info("Database connection test successful"))
                    .onFailure(error -> log.error("Database connection failed", error))
                    .mapEmpty();

        } catch (Exception e) {
            log.error("Error initializing database", e);
            return Future.failedFuture(e);
        }
    }

    private void initializeServices() {
        // Output ports (adapters)
        SettlementRepository settlementRepository = new JdbcSettlementPersistenceAdapter(jdbcPool);
        RunningTotalRepository runningTotalRepository = new JdbcRunningTotalPersistenceAdapter(jdbcPool);
        ActivityRepository activityRepository = new JdbcActivityPersistenceAdapter(jdbcPool);
        ExchangeRateRepository exchangeRateRepository = new JdbcExchangeRatePersistenceAdapter(jdbcPool);
        ConfigurationRepository configurationRepository = new InMemoryConfigurationAdapter();

        // Application services
        SettlementValidator validator = new SettlementValidator();
        SettlementIngestionUseCase ingestionUseCase = new SettlementIngestionUseCaseImpl(
                validator,
                settlementRepository,
                runningTotalRepository,
                jdbcPool,
                configurationRepository
        );

        // Input adapters (handlers)
        ingestionHandler = new SettlementIngestionHandler(ingestionUseCase);

        log.info("Services initialized successfully (Hexagonal Architecture)");
    }

    private Future<Void> startHttpServer() {
        Router router = Router.router(vertx);

        // Global handlers
        router.route().handler(LoggerHandler.create());
        router.route().handler(BodyHandler.create());

        // Setup routes
        WebRouter webRouter = new WebRouter(router, ingestionHandler);
        webRouter.setupRoutes();

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

        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(port)
                .onSuccess(server -> log.info("HTTP server listening on port {}", port))
                .mapEmpty();
    }

    private int getPort() {
        return config().getInteger("http.port", DEFAULT_PORT);
    }
}

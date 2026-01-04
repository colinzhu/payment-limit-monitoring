package com.tvpc.adapter.in.web;

import com.tvpc.adapter.in.web.ingestion.SettlementIngestionHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import lombok.RequiredArgsConstructor;

/**
 * Router configuration for settlement endpoints
 */
@RequiredArgsConstructor
public class WebRouter {

    private final Router router;
    private final SettlementIngestionHandler settlementIngestionHandler;

    public void setupRoutes() {
        // CORS headers
        router.route().handler(ctx -> {
            ctx.response()
                    .putHeader("Access-Control-Allow-Origin", "*")
                    .putHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                    .putHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")
                    .putHeader("Access-Control-Allow-Credentials", "true");
            ctx.next();
        });

        // Handle OPTIONS preflight requests
        router.options("/api/settlements").handler(ctx -> {
            ctx.response().setStatusCode(204).end();
        });

        // Settlement ingestion endpoint
        router.post("/api/settlements")
                .handler(BodyHandler.create())
                .handler(settlementIngestionHandler);

        // Health check endpoint
        router.get("/health")
                .handler(ctx -> {
                    ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .end("{\"status\":\"UP\",\"service\":\"payment-limit-monitoring\"}");
                });

        // Root endpoint
        router.get("/")
                .handler(ctx -> {
                    ctx.response()
                            .putHeader("Content-Type", "application/json")
                            .end("{\"name\":\"Payment Limit Monitoring System\",\"version\":\"1.0.0\"}");
                });
    }
}

package com.tvpc.router;

import com.tvpc.handler.SettlementIngestionHandler;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Router configuration for settlement endpoints
 */
public class SettlementRouter {

    private final Router router;
    private final SettlementIngestionHandler settlementIngestionHandler;

    public SettlementRouter(Router router, SettlementIngestionHandler settlementIngestionHandler) {
        this.router = router;
        this.settlementIngestionHandler = settlementIngestionHandler;
    }

    public void setupRoutes() {
        // CORS headers - add to all routes
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

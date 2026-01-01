package com.tvpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvpc.dto.SettlementRequest;
import com.tvpc.dto.SettlementResponse;
import com.tvpc.service.SettlementIngestionService;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP handler for settlement ingestion
 * Handles POST /api/settlements
 */
public class SettlementIngestionHandler implements Handler<RoutingContext> {
    private static final Logger log = LoggerFactory.getLogger(SettlementIngestionHandler.class);

    private final SettlementIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    public SettlementIngestionHandler(SettlementIngestionService ingestionService) {
        this.ingestionService = ingestionService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void handle(RoutingContext context) {
        log.info("Handler called, getting request body...");

        // Get request body
        RequestBody body = context.body();
        log.info("RequestBody object: {}", body);

        JsonObject requestBody = body.asJsonObject();
        log.info("Parsed JSON body: {}", requestBody);

        if (requestBody == null) {
            log.warn("Request body is null");
            sendError(context, 400, "Request body is required");
            return;
        }

        try {
            // Convert JSON to DTO
            SettlementRequest request = requestBody.mapTo(SettlementRequest.class);

            log.info("Received settlement ingestion request: {}", request.getSettlementId());

            // Process the settlement
            ingestionService.processSettlement(request)
                    .onSuccess(seqId -> {
                        SettlementResponse response = SettlementResponse.success(
                                "Settlement processed successfully",
                                seqId
                        );

                        log.info("Settlement {} processed successfully with seqId: {}",
                                request.getSettlementId(), seqId);

                        context.response()
                                .setStatusCode(201)
                                .putHeader("Content-Type", "application/json")
                                .end(JsonObject.mapFrom(response).encode());
                    })
                    .onFailure(error -> {
                        log.error("Failed to process settlement {}: {}",
                                request.getSettlementId(), error.getMessage(), error);

                        // Determine appropriate status code
                        int statusCode = 500;
                        String message = error.getMessage();

                        if (error instanceof IllegalArgumentException) {
                            statusCode = 400; // Validation error
                        }

                        SettlementResponse response = SettlementResponse.error(message);
                        context.response()
                                .setStatusCode(statusCode)
                                .putHeader("Content-Type", "application/json")
                                .end(JsonObject.mapFrom(response).encode());
                    });

        } catch (Exception e) {
            log.error("Error parsing request body", e);
            sendError(context, 400, "Invalid request format: " + e.getMessage());
        }
    }

    private void sendError(RoutingContext context, int statusCode, String message) {
        SettlementResponse response = SettlementResponse.error(message);
        context.response()
                .setStatusCode(statusCode)
                .putHeader("Content-Type", "application/json")
                .end(JsonObject.mapFrom(response).encode());
    }
}

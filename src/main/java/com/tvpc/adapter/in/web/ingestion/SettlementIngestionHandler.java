package com.tvpc.adapter.in.web.ingestion;

import com.tvpc.application.port.in.SettlementIngestionUseCase;
import com.tvpc.application.port.in.SettlementIngestionUseCase.SettlementIngestionCommand;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.RoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HTTP handler for settlement ingestion
 * Handles POST /api/settlements
 */
@Slf4j
@RequiredArgsConstructor
public class SettlementIngestionHandler implements Handler<RoutingContext> {

    private final SettlementIngestionUseCase ingestionUseCase;

    @Override
    public void handle(RoutingContext context) {
        log.info("Handler called, getting request body...");

        RequestBody body = context.body();
        JsonObject requestBody = body.asJsonObject();

        if (requestBody == null) {
            log.warn("Request body is null");
            sendError(context, 400, "Request body is required");
            return;
        }

        try {
            SettlementIngestionRequest request = requestBody.mapTo(SettlementIngestionRequest.class);
            log.info("Received settlement ingestion request: {}", request.settlementId());

            // Convert to command
            SettlementIngestionCommand command = new SettlementIngestionCommand(
                    request.settlementId(),
                    request.settlementVersion(),
                    request.pts(),
                    request.processingEntity(),
                    request.counterpartyId(),
                    request.valueDate(),
                    request.currency(),
                    request.amount(),
                    request.businessStatus(),
                    request.direction(),
                    request.settlementType()
            );

            // Process the settlement
            ingestionUseCase.processSettlement(command)
                    .onSuccess(seqId -> {
                        SettlementIngestionResponse response = SettlementIngestionResponse.success(
                                "Settlement processed successfully",
                                seqId
                        );

                        log.info("Settlement {} processed successfully with seqId: {}",
                                request.settlementId(), seqId);

                        context.response()
                                .setStatusCode(201)
                                .putHeader("Content-Type", "application/json")
                                .end(JsonObject.mapFrom(response).encode());
                    })
                    .onFailure(error -> {
                        log.error("Failed to process settlement {}: {}",
                                request.settlementId(), error.getMessage(), error);

                        int statusCode = 500;
                        String message = error.getMessage();

                        if (error instanceof IllegalArgumentException) {
                            statusCode = 400;
                        }

                        SettlementIngestionResponse response = SettlementIngestionResponse.error(message);
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
        SettlementIngestionResponse response = SettlementIngestionResponse.error(message);
        context.response()
                .setStatusCode(statusCode)
                .putHeader("Content-Type", "application/json")
                .end(JsonObject.mapFrom(response).encode());
    }
}

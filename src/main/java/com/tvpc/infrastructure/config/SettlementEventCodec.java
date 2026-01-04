package com.tvpc.infrastructure.config;

import com.tvpc.domain.event.SettlementEvent;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;

/**
 * Message codec for SettlementEvent to enable event bus communication
 */
public class SettlementEventCodec implements MessageCodec<SettlementEvent, SettlementEvent> {

    @Override
    public void encodeToWire(Buffer buffer, SettlementEvent event) {
        JsonObject json = new JsonObject()
                .put("pts", event.getPts())
                .put("processingEntity", event.getProcessingEntity())
                .put("counterpartyId", event.getCounterpartyId())
                .put("valueDate", event.getValueDate().toString())
                .put("refId", event.getRefId());

        String jsonStr = json.encode();
        buffer.appendInt(jsonStr.length());
        buffer.appendString(jsonStr);
    }

    @Override
    public SettlementEvent decodeFromWire(int pos, Buffer buffer) {
        int length = buffer.getInt(pos);
        String jsonStr = buffer.getString(pos + 4, pos + 4 + length);
        JsonObject json = new JsonObject(jsonStr);

        return new SettlementEvent(
                json.getString("pts"),
                json.getString("processingEntity"),
                json.getString("counterpartyId"),
                LocalDate.parse(json.getString("valueDate")),
                json.getLong("refId")
        );
    }

    @Override
    public SettlementEvent transform(SettlementEvent event) {
        return event;
    }

    @Override
    public String name() {
        return "SettlementEventCodec";
    }

    @Override
    public byte systemCodecID() {
        return -1;
    }
}

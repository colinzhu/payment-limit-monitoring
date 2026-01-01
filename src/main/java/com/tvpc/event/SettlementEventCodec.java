package com.tvpc.event;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.MessageCodec;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;

/**
 * Message codec for SettlementEvent to enable event bus transmission
 */
public class SettlementEventCodec implements MessageCodec<SettlementEvent, SettlementEvent> {

    @Override
    public void encodeToWire(Buffer buffer, SettlementEvent event) {
        JsonObject json = new JsonObject()
                .put("pts", event.getPts())
                .put("processingEntity", event.getProcessingEntity())
                .put("counterpartyId", event.getCounterpartyId())
                .put("valueDate", event.getValueDate().toString())
                .put("seqId", event.getSeqId());

        String encoded = json.encode();
        buffer.appendInt(encoded.length());
        buffer.appendString(encoded);
    }

    @Override
    public SettlementEvent decodeFromWire(int position, Buffer buffer) {
        int length = buffer.getInt(position);
        int offset = position + 4;
        String jsonStr = buffer.getString(offset, offset + length);

        JsonObject json = new JsonObject(jsonStr);

        return new SettlementEvent(
                json.getString("pts"),
                json.getString("processingEntity"),
                json.getString("counterpartyId"),
                LocalDate.parse(json.getString("valueDate")),
                json.getLong("seqId")
        );
    }

    @Override
    public SettlementEvent transform(SettlementEvent event) {
        // No transformation needed - just return the same object
        return event;
    }

    @Override
    public String name() {
        return "SettlementEventCodec";
    }

    @Override
    public byte systemCodecID() {
        return -1; // -1 indicates custom codec
    }
}

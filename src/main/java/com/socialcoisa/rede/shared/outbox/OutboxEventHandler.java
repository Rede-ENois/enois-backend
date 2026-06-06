package com.socialcoisa.rede.shared.outbox;

public interface OutboxEventHandler {

	void handle(String eventType, String aggregateId, String payload);
}

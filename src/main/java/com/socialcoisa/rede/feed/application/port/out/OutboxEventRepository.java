package com.socialcoisa.rede.feed.application.port.out;

public interface OutboxEventRepository {

	void append(String eventType, String aggregateId, String payload);
}

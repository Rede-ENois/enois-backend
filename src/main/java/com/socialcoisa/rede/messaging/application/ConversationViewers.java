package com.socialcoisa.rede.messaging.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Who has which conversation open on screen right now, so a new message does not notify the person
 * who is already looking at it. The app sends a heartbeat while the thread is visible and says when
 * it leaves; a tab that dies without saying so drops out after {@link #TTL}.
 *
 * <p>ponytail: in-memory, like the message stream ({@code MessageStreamHub}): correct while
 * production runs a single backend instance. A second instance needs this shared (Redis or similar).
 */
@Component
public class ConversationViewers {

	/** A bit over two heartbeats (the app sends one every 20s), so one lost request does not count. */
	static final Duration TTL = Duration.ofSeconds(45);

	private final Map<String, Map<String, Instant>> viewing = new ConcurrentHashMap<>();
	private final Clock clock;

	public ConversationViewers(Clock clock) {
		this.clock = clock;
	}

	public void viewing(String userId, String conversationId) {
		viewing.computeIfAbsent(userId, id -> new ConcurrentHashMap<>())
			.put(conversationId, clock.instant().plus(TTL));
	}

	public void left(String userId, String conversationId) {
		viewing.computeIfPresent(userId, (id, conversations) -> {
			conversations.remove(conversationId);
			return conversations.isEmpty() ? null : conversations;
		});
	}

	public boolean isViewing(String userId, String conversationId) {
		Map<String, Instant> conversations = viewing.get(userId);
		Instant until = conversations == null ? null : conversations.get(conversationId);
		return until != null && until.isAfter(clock.instant());
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
	void dropExpired() {
		Instant now = clock.instant();
		viewing.forEach((userId, conversations) -> {
			conversations.values().removeIf(until -> !until.isAfter(now));
			viewing.computeIfPresent(userId, (id, current) -> current.isEmpty() ? null : current);
		});
	}
}

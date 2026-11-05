package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.socialcoisa.rede.ops.monitoring.MonitoringEvent;
import com.socialcoisa.rede.ops.monitoring.MonitoringEventListener;
import com.socialcoisa.rede.ops.monitoring.MonitoringLogAppender;
import com.socialcoisa.rede.ops.monitoring.kofagent.Alert.Category;
import com.socialcoisa.rede.ops.monitoring.kofagent.AlertFormatter.Bulletin;
import com.socialcoisa.rede.ops.monitoring.kofagent.AlertFormatter.Entry;
import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTransport.Outcome;
import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTransport.SendResult;

/**
 * Takes monitoring events off the producers' threads and delivers them to the Telegram channel as
 * one bulletin per flush window (10 s by default).
 *
 * <p><b>Producers</b> ({@link #onEvent}) only offer to a bounded queue: no I/O, no lock, never
 * blocking a request. A full queue keeps ERROR/FATAL in a small reserve queue and counts the rest as
 * dropped; the count is printed on the next bulletin, so nothing is discarded silently.</p>
 *
 * <p><b>What goes in a bulletin</b>: log and exception events at the configured levels (WARN and
 * above by default), every 5xx, slow requests, and the events that change the picture whatever their
 * level (startup/shutdown, health transitions, admin audit, application state). Successful and 4xx
 * requests are only counted. Inside a window the same kind of event (same fingerprint) is one entry
 * with "×N" and the IPs/users that triggered it; an HTTP 5xx already reported by its exception is
 * merged into the exception entry; an event id seen twice is ignored.</p>
 *
 * <p><b>One dispatcher thread</b> owns all that state (no locks), and also the sending: the
 * per-minute rate limit, retries with exponential backoff and jitter, Telegram's
 * {@code retry_after}, and the circuit breaker. A single sender is also what Telegram wants.</p>
 *
 * <p><b>Critical bulletins</b> (any ERROR, FATAL, lifecycle or health entry) are written to the
 * {@link AlertSpool} before the first attempt and deleted only after Telegram confirms them. During
 * an outage they wait on disk; when the circuit lets a probe through again (or the app restarts)
 * they are replayed oldest first.</p>
 */
public final class KofAgentDispatcher implements MonitoringEventListener {

	private static final Logger LOG = LoggerFactory.getLogger(KofAgentDispatcher.class);
	private static final int RESERVE_CAPACITY = 1000;
	private static final int OUTBOX_CAPACITY = 500;
	private static final int MAX_WINDOW_ENTRIES = 5000;
	private static final int MAX_TRACKED_VALUES = 50;
	private static final int SEEN_IDS = 20_000;
	private static final int SEEN_REQUESTS = 5000;
	private static final int REPLAY_BATCH = 20;
	private static final int DRAIN_PER_TICK = 20_000;
	private static final int MESSAGES_PER_SEND = 3;

	private final KofAgentProperties properties;
	private final KofAgentTransport transport;
	private final AlertSpool spool;
	private final Clock clock;
	private final AlertClassifier classifier;
	private final AlertFormatter formatter;
	private final BlockingQueue<MonitoringEvent> inbound;
	private final BlockingQueue<MonitoringEvent> reserve = new ArrayBlockingQueue<>(RESERVE_CAPACITY);
	private final CircuitBreaker breaker;
	private final TokenBucket bucket;

	// Dispatcher-thread state (also touched by shutdown() after the thread has been joined).
	private final Deque<Outgoing> priority = new ArrayDeque<>();
	private final Deque<Outgoing> normal = new ArrayDeque<>();
	private final Set<String> spoolKeysInMemory = new HashSet<>();
	private final Map<String, Boolean> seenIds = lru(SEEN_IDS);
	private final Map<String, Boolean> exceptionRequests = lru(SEEN_REQUESTS);
	private final Map<String, WindowEntry> window = new LinkedHashMap<>();
	private Instant windowStart;
	private long windowRequests;
	private long window4xx;
	private long window5xx;
	private long windowMerged;
	private long windowOverflow;
	private long droppedReported;
	private int parkedInSpool;
	private int attempt;
	private Instant nextAttemptAt = Instant.MIN;

	// Counters read by metrics and printed on bulletins.
	private final Map<Severity, AtomicLong> droppedQueueFull = new EnumMap<>(Severity.class);
	final AtomicLong accepted = new AtomicLong();
	final AtomicLong duplicates = new AtomicLong();
	final AtomicLong grouped = new AtomicLong();
	final AtomicLong countedOnly = new AtomicLong();
	final AtomicLong bulletins = new AtomicLong();
	final AtomicLong sentMessages = new AtomicLong();
	final AtomicLong failedAttempts = new AtomicLong();
	final AtomicLong droppedAfterRetry = new AtomicLong();
	final AtomicLong droppedOutboxFull = new AtomicLong();
	final AtomicLong deadLettered = new AtomicLong();
	final AtomicLong replayed = new AtomicLong();
	private final AtomicLong spoolFailures = new AtomicLong();
	private volatile int outboxSize;
	private volatile int spoolSize;

	private volatile boolean active;
	private volatile boolean running;
	private Thread worker;

	KofAgentDispatcher(KofAgentProperties properties, KofAgentTransport transport, AlertSpool spool, Clock clock) {
		this.properties = properties;
		this.transport = transport;
		this.spool = spool;
		this.clock = clock;
		this.classifier = new AlertClassifier(properties.slowRequest());
		this.formatter = new AlertFormatter(properties.serviceName(), properties.environment(),
			java.time.ZoneId.of(properties.zone()));
		this.inbound = new ArrayBlockingQueue<>(properties.queueCapacity());
		this.breaker = new CircuitBreaker(properties.circuit().failureThreshold(), properties.circuit().openDuration(),
			properties.circuit().maxOpenDuration());
		this.bucket = new TokenBucket(properties.rate().messagesPerMinute(), properties.rate().burst());
		for (Severity severity : Severity.values()) {
			droppedQueueFull.put(severity, new AtomicLong());
		}
		this.windowStart = clock.instant();
	}

	// ------------------------------------------------------------------ producers (any thread)

	@Override
	public boolean accepts(String level, String type) {
		if (!active) {
			return false;
		}
		return switch (type == null ? "" : type) {
			case "REQUEST", "EXCEPTION", "LIFECYCLE", "HEALTH", "AUDIT" -> true;
			default -> properties.levels().contains(Severity.of(level));
		};
	}

	@Override
	public void onEvent(MonitoringEvent event) {
		if (event == null || !accepts(event.level(), event.type())) {
			return;
		}
		if (inbound.offer(event)) {
			return;
		}
		Severity severity = Severity.of(event.level());
		if (severity.atLeast(Severity.ERROR) && reserve.offer(event)) {
			return;
		}
		droppedQueueFull.get(severity).incrementAndGet();
	}

	// ------------------------------------------------------------------ lifecycle

	/** Activates the listener side; the caller decides whether to also start the thread. */
	void activate() {
		parkedInSpool = spool.size();
		spoolSize = parkedInSpool;
		windowStart = clock.instant();
		active = true;
	}

	void startThread() {
		running = true;
		worker = new Thread(this::loop, MonitoringLogAppender.INTERNAL_THREAD_PREFIX + "kof-agent");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Stops accepting, lets the thread finish its tick, closes the current window and drains on the
	 * caller's thread until the outbox is empty or the shutdown budget is spent. Critical leftovers
	 * are already on disk and go out on the next start.
	 */
	void shutdown() {
		running = false;
		if (worker != null) {
			worker.interrupt();
			try {
				worker.join(2000);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		active = false;
		// Real elapsed time bounds the drain, whatever the injected clock does.
		long deadlineNanos = System.nanoTime() + properties.shutdownTimeout().toNanos();
		Instant deadline = clock.instant().plus(properties.shutdownTimeout());
		drainInbound();
		closeWindow(clock.instant());
		while (outboxSize() > 0 && System.nanoTime() < deadlineNanos
				&& breaker.state() != CircuitBreaker.State.OPEN && nextAttemptAt.isBefore(deadline)) {
			if (!trySend(clock.instant())) {
				sleepQuietly(Math.min(250, (deadlineNanos - System.nanoTime()) / 1_000_000));
			}
		}
		if (outboxSize() > 0) {
			long onDisk = priority.stream().filter(message -> message.spoolKey() != null).count();
			LOG.warn("kof_agent_shutdown_pending messages={} critical_on_disk={}", outboxSize(), onDisk);
		}
	}

	private void loop() {
		SendResult check = transport.verify();
		if (check.outcome() == Outcome.SENT) {
			LOG.info("kof_agent_ready {} environment={}", check.detail(), properties.environment());
		}
		else {
			LOG.error("kof_agent_channel_unreachable outcome={} detail={}", check.outcome(), check.detail());
		}
		// Wake at least once a second: windows close on time and retries fire on time.
		long waitMs = Math.min(1000, Math.max(50, properties.flushInterval().toMillis()));
		while (running) {
			try {
				MonitoringEvent first = inbound.poll(waitMs, TimeUnit.MILLISECONDS);
				if (first != null) {
					process(first);
				}
				tick();
			}
			catch (InterruptedException interrupted) {
				if (!running) {
					return;
				}
			}
			catch (RuntimeException failure) {
				// The dispatcher must survive any bug in formatting or I/O; the event is lost, the loop is not.
				LOG.error("kof_agent_loop_failed type={}", failure.getClass().getSimpleName());
			}
		}
	}

	/** One iteration: ingest, close the window when due, replay, send. Tests call it with a fixed clock. */
	void tick() {
		Instant now = clock.instant();
		drainInbound();
		if (!now.isBefore(windowStart.plus(properties.flushInterval()))) {
			closeWindow(now);
		}
		replayFromSpool(now);
		trySend(now);
		outboxSize = outboxSize();
	}

	// ------------------------------------------------------------------ ingest

	private void drainInbound() {
		int drained = 0;
		MonitoringEvent event;
		while (drained < DRAIN_PER_TICK && (event = reserve.poll()) != null) {
			process(event);
			drained++;
		}
		while (drained < DRAIN_PER_TICK && (event = inbound.poll()) != null) {
			process(event);
			drained++;
		}
	}

	void process(MonitoringEvent event) {
		if (event.id() != null && seenIds.put(event.id(), Boolean.TRUE) != null) {
			duplicates.incrementAndGet();
			return;
		}
		accepted.incrementAndGet();
		boolean request = "REQUEST".equalsIgnoreCase(event.type());
		if (request) {
			windowRequests++;
			if (event.status() != null && event.status() >= 500) {
				window5xx++;
			}
			else if (event.status() != null && event.status() >= 400) {
				window4xx++;
			}
		}
		if (request && event.status() != null && event.status() >= 500 && event.requestId() != null
				&& exceptionRequests.containsKey(event.requestId())) {
			// The exception entry already carries endpoint, status, IP, user and the stack.
			windowMerged++;
			return;
		}
		if ("EXCEPTION".equalsIgnoreCase(event.type()) && event.requestId() != null) {
			exceptionRequests.put(event.requestId(), Boolean.TRUE);
		}
		Alert alert = classifier.classify(event);
		if (!listed(alert, request)) {
			countedOnly.incrementAndGet();
			return;
		}
		WindowEntry entry = window.get(alert.fingerprint());
		if (entry != null) {
			entry.add(alert);
			grouped.incrementAndGet();
			return;
		}
		if (window.size() < MAX_WINDOW_ENTRIES) {
			window.put(alert.fingerprint(), new WindowEntry(alert));
		}
		else {
			windowOverflow++;
		}
	}

	/** Requests are listed only when something went wrong; the important categories always are. */
	private boolean listed(Alert alert, boolean request) {
		Category category = alert.category();
		if (category == Category.LIFECYCLE || category == Category.HEALTH || category == Category.AUDIT
				|| category == Category.STATE) {
			return true;
		}
		if (request) {
			return alert.status() != null && alert.status() >= 500
				|| alert.severity() == Severity.WARN && alert.status() != null && alert.status() < 400
					&& properties.levels().contains(Severity.WARN);
		}
		return properties.levels().contains(alert.severity());
	}

	/** Turns the window into a bulletin (if anything worth listing happened) and starts a new one. */
	private void closeWindow(Instant now) {
		long dropped = droppedTotal();
		long newlyDropped = dropped - droppedReported;
		if (!window.isEmpty()) {
			List<WindowEntry> entries = new ArrayList<>(window.values());
			entries.sort(Comparator.comparing((WindowEntry entry) -> entry.alert.severity()).reversed());
			int limit = Math.min(entries.size(), properties.batchMaxEvents());
			List<Entry> listed = new ArrayList<>(limit);
			boolean critical = false;
			boolean loud = false;
			for (WindowEntry entry : entries.subList(0, limit)) {
				listed.add(entry.toEntry());
				critical |= entry.alert.critical();
				loud |= entry.alert.severity().atLeast(Severity.ERROR) || entry.alert.category() == Category.HEALTH;
			}
			Bulletin bulletin = new Bulletin(windowStart, now, listed, entries.size() - limit + windowOverflow,
				windowRequests, window4xx, window5xx, windowMerged, channelIssues(newlyDropped));
			for (String text : formatter.format(bulletin)) {
				enqueue(new Outgoing(null, entries.getFirst().alert.severity(), text, critical, !loud, now, 1));
			}
			bulletins.incrementAndGet();
			droppedReported = dropped;
		}
		window.clear();
		windowStart = now;
		windowRequests = 0;
		window4xx = 0;
		window5xx = 0;
		windowMerged = 0;
		windowOverflow = 0;
	}

	private String channelIssues(long newlyDropped) {
		List<String> issues = new ArrayList<>();
		if (newlyDropped > 0) {
			issues.add(newlyDropped + " eventos descartados");
		}
		if (breaker.state() != CircuitBreaker.State.CLOSED) {
			issues.add("envio instável, circuito " + breaker.state().name());
		}
		if (parkedInSpool > 0) {
			issues.add(parkedInSpool + " boletins aguardando no disco");
		}
		return String.join(", ", issues);
	}

	// ------------------------------------------------------------------ outbox

	private void enqueue(Outgoing message) {
		if (message.critical()) {
			String key = spool.write(message, spoolSize);
			if (key == null) {
				spoolFailures.incrementAndGet();
			}
			else {
				message = message.withSpoolKey(key);
				spoolKeysInMemory.add(key);
				spoolSize++;
			}
			priority.addLast(message);
		}
		else {
			normal.addLast(message);
		}
		while (outboxSize() > OUTBOX_CAPACITY) {
			if (!normal.isEmpty()) {
				normal.pollFirst();
				droppedOutboxFull.incrementAndGet();
				continue;
			}
			Outgoing oldest = priority.pollFirst();
			if (oldest.spoolKey() != null) {
				park(oldest);
			}
			else {
				droppedOutboxFull.incrementAndGet();
			}
		}
		outboxSize = outboxSize();
	}

	/** Keeps a critical message on disk only; it comes back through {@link #replayFromSpool}. */
	private void park(Outgoing message) {
		spoolKeysInMemory.remove(message.spoolKey());
		parkedInSpool++;
	}

	private void replayFromSpool(Instant now) {
		// Not while OPEN; HALF_OPEN is fine: the replayed message is the probe that closes the circuit.
		if (parkedInSpool <= 0 || priority.size() >= MESSAGES_PER_SEND || now.isBefore(nextAttemptAt)
				|| !breaker.allows(now)) {
			return;
		}
		List<Outgoing> loaded = spool.load(spoolKeysInMemory, REPLAY_BATCH);
		if (loaded.isEmpty()) {
			parkedInSpool = 0;
			return;
		}
		// Older than anything in memory: they go to the front, keeping chronological order.
		for (int i = loaded.size() - 1; i >= 0; i--) {
			Outgoing message = loaded.get(i);
			spoolKeysInMemory.add(message.spoolKey());
			priority.addFirst(message);
		}
		parkedInSpool = Math.max(0, parkedInSpool - loaded.size());
		replayed.addAndGet(loaded.size());
		LOG.info("kof_agent_replay messages={}", loaded.size());
	}

	/** @return true when something was sent or definitively handled */
	boolean trySend(Instant now) {
		if (outboxSize() == 0 || now.isBefore(nextAttemptAt) || !breaker.allows(now) || !bucket.tryAcquire(now)) {
			return false;
		}
		List<Outgoing> batch = takeBatch();
		String text = String.join(AlertFormatter.SEPARATOR, batch.stream().map(Outgoing::text).toList());
		boolean silent = batch.stream().allMatch(Outgoing::silent);
		SendResult result = transport.send(text, silent, true);
		if (result.outcome() == Outcome.BAD_MARKUP) {
			result = transport.send(AlertFormatter.stripHtml(text), silent, false);
		}
		switch (result.outcome()) {
			case SENT -> delivered(batch);
			case RATE_LIMITED -> {
				putBack(batch);
				nextAttemptAt = now.plus(result.retryAfter());
				LOG.warn("kof_agent_rate_limited retry_after_s={}", result.retryAfter().toSeconds());
			}
			case REJECTED -> {
				for (Outgoing message : batch) {
					if (message.spoolKey() != null) {
						spool.deadLetter(message.spoolKey());
						spoolKeysInMemory.remove(message.spoolKey());
						spoolSize = Math.max(0, spoolSize - 1);
					}
				}
				deadLettered.addAndGet(batch.size());
				LOG.error("kof_agent_message_rejected messages={} detail={}", batch.size(), result.detail());
			}
			default -> failed(batch, result, now);
		}
		outboxSize = outboxSize();
		return result.outcome() == Outcome.SENT || result.outcome() == Outcome.REJECTED;
	}

	private void delivered(List<Outgoing> batch) {
		for (Outgoing message : batch) {
			if (message.spoolKey() != null) {
				spool.delete(message.spoolKey());
				spoolKeysInMemory.remove(message.spoolKey());
				spoolSize = Math.max(0, spoolSize - 1);
			}
		}
		sentMessages.incrementAndGet();
		attempt = 0;
		nextAttemptAt = Instant.MIN;
		if (breaker.onSuccess()) {
			LOG.info("kof_agent_circuit_closed parked_on_disk={}", parkedInSpool);
		}
	}

	private void failed(List<Outgoing> batch, SendResult result, Instant now) {
		failedAttempts.incrementAndGet();
		attempt++;
		if (breaker.onFailure(now)) {
			LOG.warn("kof_agent_circuit_open until={} outcome={} detail={}", breaker.openUntil(), result.outcome(),
				result.detail());
		}
		int attemptForBackoff = attempt;
		if (attempt >= properties.retry().maxAttempts()) {
			// Retries exhausted: critical bulletins wait on disk for the recovery replay, the rest is
			// counted as lost (and printed on the next bulletin).
			for (Outgoing message : batch) {
				if (message.spoolKey() != null) {
					park(message);
				}
				else {
					droppedAfterRetry.incrementAndGet();
				}
			}
			LOG.warn("kof_agent_retries_exhausted messages={} outcome={} detail={}", batch.size(), result.outcome(),
				result.detail());
			attempt = 0;
		}
		else {
			putBack(batch);
		}
		nextAttemptAt = now.plus(backoff(attemptForBackoff));
	}

	/** initial * 2^(attempt-1), capped, with +/-20% jitter so restarts do not retry in lockstep. */
	Duration backoff(int attemptNumber) {
		long initial = properties.retry().initialBackoff().toMillis();
		long cap = properties.retry().maxBackoff().toMillis();
		long base = Math.min(cap, initial * (1L << Math.min(Math.max(0, attemptNumber - 1), 30)));
		double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
		return Duration.ofMillis(Math.max(1, Math.min(cap, Math.round(base * jitter))));
	}

	/** Several queued messages share one Telegram message while they fit (catching up after an outage). */
	private List<Outgoing> takeBatch() {
		List<Outgoing> batch = new ArrayList<>();
		int length = 0;
		for (Deque<Outgoing> source : List.of(priority, normal)) {
			while (!source.isEmpty() && batch.size() < MESSAGES_PER_SEND) {
				Outgoing next = source.peekFirst();
				int added = next.text().length() + (batch.isEmpty() ? 0 : AlertFormatter.SEPARATOR.length());
				if (!batch.isEmpty() && length + added > AlertFormatter.MAX_MESSAGE) {
					return batch;
				}
				batch.add(source.pollFirst());
				length += added;
			}
		}
		return batch;
	}

	private void putBack(List<Outgoing> batch) {
		for (int i = batch.size() - 1; i >= 0; i--) {
			Outgoing message = batch.get(i);
			(message.critical() ? priority : normal).addFirst(message);
		}
	}

	private int outboxSize() {
		return priority.size() + normal.size();
	}

	// ------------------------------------------------------------------ observability of the channel

	long droppedTotal() {
		long total = droppedAfterRetry.get() + droppedOutboxFull.get() + spoolFailures.get();
		for (AtomicLong count : droppedQueueFull.values()) {
			total += count.get();
		}
		return total;
	}

	long droppedQueueFull(Severity severity) {
		return droppedQueueFull.get(severity).get();
	}

	int queueSize() {
		return inbound.size() + reserve.size();
	}

	int pendingMessages() {
		return outboxSize;
	}

	int spoolSize() {
		return spoolSize;
	}

	CircuitBreaker.State circuitState() {
		return breaker.state();
	}

	boolean isActive() {
		return active;
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(Math.max(1, millis));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static <K> Map<K, Boolean> lru(int max) {
		return new LinkedHashMap<>(256, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, Boolean> eldest) {
				return size() > max;
			}
		};
	}

	/** Accumulates one fingerprint inside the window: count, last time, who triggered it. */
	private static final class WindowEntry {
		private final Alert alert;
		private long count = 1;
		private Instant lastAt;
		private final Set<String> ips = new LinkedHashSet<>();
		private final Set<String> users = new LinkedHashSet<>();

		private WindowEntry(Alert alert) {
			this.alert = alert;
			this.lastAt = alert.at();
			track(alert);
		}

		private void add(Alert next) {
			count++;
			if (next.at() != null && next.at().isAfter(lastAt)) {
				lastAt = next.at();
			}
			track(next);
		}

		private void track(Alert next) {
			if (next.clientIp() != null && ips.size() < MAX_TRACKED_VALUES) {
				ips.add(next.clientIp());
			}
			if (next.userId() != null && users.size() < MAX_TRACKED_VALUES) {
				users.add(next.userId());
			}
		}

		private Entry toEntry() {
			return new Entry(alert, count, lastAt, ips, ips.size(), users, users.size());
		}
	}
}

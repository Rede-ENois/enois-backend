package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns individual slow requests into one state: is the API slow right now?
 *
 * <p>Requests are counted in fixed windows (5 min by default). When a window closes with enough
 * traffic ({@code minRequests}) and at least {@code slowRatio} of it slow, the API is degraded;
 * a later window with enough traffic below the ratio means it recovered. Only these two transitions
 * produce an alert, so a route that is always a bit slow never pages anyone (it shows up in the
 * digest instead). A window with too little traffic to judge keeps the current state.</p>
 */
final class LatencyWatch {

	private static final int TOP_ROUTES = 3;
	private static final int MAX_ROUTES = 500;

	private final Duration window;
	private final int minRequests;
	private final double slowRatio;
	private Instant windowStart;
	private long requests;
	private long slow;
	private final Map<String, Long> slowByRoute = new HashMap<>();
	private boolean degraded;

	LatencyWatch(KofAgentProperties.Latency latency, Instant now) {
		this.window = latency.window();
		this.minRequests = latency.minRequests();
		this.slowRatio = latency.slowRatio();
		this.windowStart = now;
	}

	void record(String route, boolean isSlow) {
		requests++;
		if (isSlow) {
			slow++;
			if (route != null && (slowByRoute.containsKey(route) || slowByRoute.size() < MAX_ROUTES)) {
				slowByRoute.merge(route, 1L, Long::sum);
			}
		}
	}

	/** Closes the window when due; returns the transition it caused, or {@code null} when nothing changed. */
	Transition roll(Instant now) {
		if (now.isBefore(windowStart.plus(window))) {
			return null;
		}
		Transition transition = null;
		if (requests >= minRequests) {
			boolean slowNow = slow >= Math.ceil(slowRatio * requests);
			if (slowNow != degraded) {
				degraded = slowNow;
				transition = new Transition(slowNow, windowStart, now, requests, slow, topRoutes());
			}
		}
		windowStart = now;
		requests = 0;
		slow = 0;
		slowByRoute.clear();
		return transition;
	}

	private List<Map.Entry<String, Long>> topRoutes() {
		return slowByRoute.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed())
			.limit(TOP_ROUTES)
			.map(entry -> Map.entry(entry.getKey(), entry.getValue()))
			.toList();
	}

	/**
	 * @param degraded true when the API became slow, false when it recovered
	 * @param routes routes with the most slow requests in the window, most first
	 */
	record Transition(boolean degraded, Instant from, Instant to, long requests, long slow,
			List<Map.Entry<String, Long>> routes) {
	}
}

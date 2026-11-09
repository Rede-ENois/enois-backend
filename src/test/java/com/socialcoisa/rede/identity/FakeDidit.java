package com.socialcoisa.rede.identity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Didit falsa para a suíte: servidor HTTP de verdade numa porta local.
 *
 * <p>O que o teste tranca é o contrato: a sessão só nasce com {@code x-api-key} e o corpo certo; a
 * aprovação só aparece quando o {@code GET .../decision/} devolve {@code "Approved"}. Nenhum
 * documento, selfie ou dado pessoal trafega aqui — a resposta de decisão é só o status.
 */
final class FakeDidit {

	record Received(String path, String apiKey, String body) {
	}

	private final HttpServer server;
	private final Map<String, Received> received = new ConcurrentHashMap<>();
	private final Map<String, String> sessionStatus = new ConcurrentHashMap<>();
	private final AtomicInteger sessionCreates = new AtomicInteger();
	private final AtomicInteger balanceReads = new AtomicInteger();
	private int nextSession = 1;

	/** Porta 0: o proprio sistema escolhe, entao duas suites nao brigam pela mesma porta. */
	FakeDidit() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	void stop() {
		server.stop(0);
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	Received last(String path) {
		return received.get(path);
	}

	int sessionCreates() {
		return sessionCreates.get();
	}

	int balanceReads() {
		return balanceReads.get();
	}

	/** O status que a consulta de decisão vai devolver para a sessão criada. */
	void setStatus(String sessionId, String status) {
		sessionStatus.put(sessionId, status);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String apiKey = exchange.getRequestHeaders().getFirst("x-api-key");
		if ("POST".equals(exchange.getRequestMethod()) && "/v3/session/".equals(path)) {
			sessionCreates.incrementAndGet();
			received.put("/v3/session/", new Received(path, apiKey, body));
			if (apiKey == null || apiKey.isBlank()) {
				respond(exchange, 401, "{\"detail\":\"missing key\"}");
				return;
			}
			String sessionId = "session-" + nextSession++;
			sessionStatus.put(sessionId, "Not Started");
			respond(exchange, 201, "{\"session_id\":\"" + sessionId + "\",\"session_kind\":\"user\","
				+ "\"status\":\"Not Started\",\"url\":\"https://verify.didit.me/en/session/" + sessionId + "\"}");
			return;
		}
		if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/v3/session/")
				&& path.endsWith("/decision/")) {
			String sessionId = path.substring("/v3/session/".length(), path.length() - "/decision/".length());
			received.put(path, new Received(path, apiKey, body));
			String status = sessionStatus.getOrDefault(sessionId, "Not Started");
			respond(exchange, 200, "{\"session_id\":\"" + sessionId + "\",\"session_kind\":\"user\","
				+ "\"status\":\"" + status + "\"}");
			return;
		}
		if ("GET".equals(exchange.getRequestMethod()) && "/v3/billing/balance/".equals(path)) {
			balanceReads.incrementAndGet();
			respond(exchange, 200, "{\"balance\":\"9.34\",\"auto_refill_enabled\":false,"
				+ "\"auto_refill_amount\":null,\"auto_refill_threshold\":null}");
			return;
		}
		respond(exchange, 404, "{}");
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
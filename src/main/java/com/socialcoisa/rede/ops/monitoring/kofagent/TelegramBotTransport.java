package com.socialcoisa.rede.ops.monitoring.kofagent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.socialcoisa.rede.ops.monitoring.SensitiveDataRedactor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Telegram Bot API client for the Kof Agent bot ({@code sendMessage}, {@code getMe}, {@code getChat}).
 *
 * <p>The token only lives in the request URL; URLs are never logged and every detail returned to the
 * caller goes through the redactor, so the token cannot leak through an exception message.</p>
 */
final class TelegramBotTransport implements KofAgentTransport {

	private final String baseUrl;
	private final String token;
	private final String chatId;
	private final Duration timeout;
	private final ObjectMapper mapper;
	private final HttpClient client;

	TelegramBotTransport(KofAgentProperties properties, ObjectMapper mapper) {
		this.baseUrl = properties.apiBaseUrl();
		this.token = properties.apiToken();
		this.chatId = properties.chatId();
		this.timeout = properties.requestTimeout();
		this.mapper = mapper;
		this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	@Override
	public SendResult send(String text, boolean silent, boolean html) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("chat_id", chatId);
		body.put("text", text);
		if (html) {
			body.put("parse_mode", "HTML");
		}
		body.put("disable_notification", silent);
		body.put("link_preview_options", Map.of("is_disabled", true));
		return call("sendMessage", body);
	}

	@Override
	public SendResult verify() {
		SendResult me = call("getMe", Map.of());
		if (me.outcome() != Outcome.SENT) {
			return me;
		}
		SendResult chat = call("getChat", Map.of("chat_id", chatId));
		if (chat.outcome() != Outcome.SENT) {
			return chat;
		}
		return new SendResult(Outcome.SENT, null, me.detail() + " " + chat.detail());
	}

	private SendResult call(String method, Map<String, Object> body) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/bot" + token + "/" + method))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			return interpret(method, response.statusCode(), response.body());
		}
		catch (IOException failure) {
			return SendResult.of(Outcome.RETRY, failure.getClass().getSimpleName());
		}
		catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			return SendResult.of(Outcome.RETRY, "interrupted");
		}
		catch (RuntimeException failure) {
			return SendResult.of(Outcome.RETRY, safe(failure.getClass().getSimpleName() + " " + failure.getMessage()));
		}
	}

	private SendResult interpret(String method, int status, String raw) {
		JsonNode json = parse(raw);
		String description = json == null ? "" : json.path("description").asString("");
		if (status == 200 && json != null && json.path("ok").asBoolean(false)) {
			return new SendResult(Outcome.SENT, null, summary(method, json.path("result")));
		}
		String detail = safe(status + " " + description);
		if (status == 429) {
			long seconds = json == null ? 5 : json.path("parameters").path("retry_after").asLong(5);
			return new SendResult(Outcome.RATE_LIMITED, Duration.ofSeconds(Math.max(1, seconds)), detail);
		}
		if (status >= 500) {
			return SendResult.of(Outcome.RETRY, detail);
		}
		String lower = description.toLowerCase(Locale.ROOT);
		if (status == 400 && lower.contains("can't parse entities")) {
			return SendResult.of(Outcome.BAD_MARKUP, detail);
		}
		if (status == 401 || status == 403 || status == 404 || lower.contains("chat not found")
				|| lower.contains("not enough rights") || lower.contains("bot was kicked")) {
			return SendResult.of(Outcome.CONFIG_ERROR, detail);
		}
		if (status == 400) {
			return SendResult.of(Outcome.REJECTED, detail);
		}
		return SendResult.of(Outcome.RETRY, detail);
	}

	private static String summary(String method, JsonNode result) {
		return switch (method) {
			case "getMe" -> "bot=@" + result.path("username").asString("?");
			case "getChat" -> "chat_type=" + result.path("type").asString("?");
			default -> null;
		};
	}

	private JsonNode parse(String raw) {
		try {
			return raw == null || raw.isBlank() ? null : mapper.readTree(raw);
		}
		catch (RuntimeException ignored) {
			return null;
		}
	}

	private static String safe(String detail) {
		return AlertClassifier.clip(SensitiveDataRedactor.redactLine(detail), 300);
	}
}

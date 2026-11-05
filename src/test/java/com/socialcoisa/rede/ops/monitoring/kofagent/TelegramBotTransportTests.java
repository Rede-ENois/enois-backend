package com.socialcoisa.rede.ops.monitoring.kofagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.ops.monitoring.kofagent.KofAgentTransport.Outcome;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The Bot API client against a local server that answers like api.telegram.org. */
class TelegramBotTransportTests {

	private static final String TOKEN = "999999:fake-token-for-tests-only-0000000000";

	private HttpServer server;
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final List<String> bodies = new CopyOnWriteArrayList<>();
	private volatile int status = 200;
	private volatile String response = "{\"ok\":true,\"result\":{\"message_id\":1}}";

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			paths.add(exchange.getRequestURI().getPath());
			bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private TelegramBotTransport transport(String baseUrl) {
		return new TelegramBotTransport(new KofAgentProperties(true, TOKEN, "-1001234567890", baseUrl, "qa", null,
			EnumSet.allOf(Severity.class), 0, null, null, null, null, 0, Path.of("unused").toString(), 0, null,
			Duration.ofSeconds(2), null, null), new ObjectMapper());
	}

	private TelegramBotTransport transport() {
		return transport("http://127.0.0.1:" + server.getAddress().getPort());
	}

	@Test
	void sendsHtmlToTheConfiguredChatThroughTheBotEndpoint() {
		KofAgentTransport.SendResult result = transport().send("<b>ok</b>", true, true);

		assertThat(result.outcome()).isEqualTo(Outcome.SENT);
		assertThat(paths).containsExactly("/bot" + TOKEN + "/sendMessage");
		JsonNode body = new ObjectMapper().readTree(bodies.getFirst());
		assertThat(body.path("chat_id").asString()).isEqualTo("-1001234567890");
		assertThat(body.path("parse_mode").asString()).isEqualTo("HTML");
		assertThat(body.path("disable_notification").asBoolean()).isTrue();
		assertThat(body.path("text").asString()).isEqualTo("<b>ok</b>");
	}

	@Test
	void mapsTelegramAnswersToOutcomes() {
		status = 429;
		response = "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 17\","
			+ "\"parameters\":{\"retry_after\":17}}";
		KofAgentTransport.SendResult limited = transport().send("x", false, true);
		assertThat(limited.outcome()).isEqualTo(Outcome.RATE_LIMITED);
		assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(17));

		status = 400;
		response = "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: can't parse entities\"}";
		assertThat(transport().send("x", false, true).outcome()).isEqualTo(Outcome.BAD_MARKUP);

		response = "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}";
		assertThat(transport().send("x", false, true).outcome()).isEqualTo(Outcome.CONFIG_ERROR);

		response = "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is too long\"}";
		assertThat(transport().send("x", false, true).outcome()).isEqualTo(Outcome.REJECTED);

		status = 403;
		response = "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot is not a member of the channel chat\"}";
		assertThat(transport().send("x", false, true).outcome()).isEqualTo(Outcome.CONFIG_ERROR);

		status = 502;
		response = "<html>bad gateway</html>";
		assertThat(transport().send("x", false, true).outcome()).isEqualTo(Outcome.RETRY);
	}

	@Test
	void networkFailureIsRetryableAndNeverExposesTheToken() {
		KofAgentTransport.SendResult result = transport("http://127.0.0.1:1").send("x", false, true);

		assertThat(result.outcome()).isEqualTo(Outcome.RETRY);
		assertThat(String.valueOf(result.detail())).doesNotContain(TOKEN).doesNotContain("fake-token");
	}

	@Test
	void verifyChecksTheBotAndTheChat() {
		response = "{\"ok\":true,\"result\":{\"username\":\"Kof4j_agent_bot\",\"type\":\"channel\"}}";
		KofAgentTransport.SendResult result = transport().verify();

		assertThat(result.outcome()).isEqualTo(Outcome.SENT);
		assertThat(result.detail()).isEqualTo("bot=@Kof4j_agent_bot chat_type=channel");
		assertThat(paths).containsExactly("/bot" + TOKEN + "/getMe", "/bot" + TOKEN + "/getChat");
	}

	@Test
	void propertiesNeverPrintTheToken() {
		assertThat(transport().toString()).doesNotContain(TOKEN);
		assertThat(new KofAgentProperties(true, TOKEN, "-100", null, null, null, null, 0, null, null, null, null, 0,
			null, 0, null, null, null, null).toString()).doesNotContain(TOKEN).contains("<redacted>");
	}
}

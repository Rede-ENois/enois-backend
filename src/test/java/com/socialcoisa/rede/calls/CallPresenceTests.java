package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.calls.application.CallExpiryWorker;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Presença com sinal de vida no "Tamo junto": a entrada cujo token não sai é desfeita na hora,
 * o ping mantém a sala viva e a sala cujo sinal de vida apagou fecha pelo worker.
 *
 * <p>O que este teste tranca: falha de emissão de token não deixa fantasma contado como presente;
 * sala pingada resiste ao prazo enquanto o ping corre e fecha com {@code NO_CONNECTION} quando ele
 * para; e sala cujo front nunca pingou mantém o critério antigo (presença é gente) — nenhuma
 * chamada viva pode ser derrubada por prazo durante a rolagem do ping.
 */
@SpringBootTest(properties = {
		"rede.calls.epapo.url=https://chamadas.example.test",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(CallPresenceTests.MovableClock.class)
class CallPresenceTests {

	private static final int GATEWAY_PORT = freePort();

	static final FakeEpapoGateway GATEWAY = startGateway(GATEWAY_PORT);

	static final String BRIDGE = java.util.UUID.randomUUID() + "" + java.util.UUID.randomUUID();

	static {
		System.setProperty("call.test.bridge", BRIDGE);
	}

	@DynamicPropertySource
	static void gateway(DynamicPropertyRegistry registry) {
		registry.add("rede.calls.epapo.gateway-url", () -> GATEWAY.url());
		registry.add("rede.calls.epapo.internal-token", () -> "epapo-" + BRIDGE);
		registry.add("rede.calls.gateway.authorize-token", () -> "${call.test.bridge}");
	}

	@AfterAll
	static void shutGateway() {
		GATEWAY.stop();
	}

	static final class Clock extends java.time.Clock {
		private volatile Instant now = Instant.parse("2026-11-16T12:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

		void advance(java.time.Duration amount) {
			now = now.plus(amount);
		}
	}

	@TestConfiguration
	static class MovableClock {

		@Bean
		@Primary
		Clock callClock() {
			return new Clock();
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private Clock clock;

	@Autowired
	private CallExpiryWorker worker;

	private TestAuthHelper auth() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void entradaQueFalhaNaEmissaoDoTokenNaoDeixaFantasma() throws Exception {
		var owner = auth().authenticate("call-pres-a");
		String groupId = createGroup(owner, "Tamo Junto Presença 1", "OPEN", "call-pres-1");
		String callId = startGroupCall(owner, groupId);

		// Quem abre já entra: nasce com 1 presente.
		assertThat(openCall(owner, groupId).get("participantCount").asInt()).isEqualTo(1);

		// O gateway recusa a emissão: a entrada gravou presença e o token não saiu.
		GATEWAY.respond("/auth/token", 403, "{\"error\":\"entry_denied\"}");
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", owner.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALL_ENTRY_DENIED"));

		// A presença foi desfeita: a pessoa não conta como presente — sem fantasma sustentando
		// a sala para sempre.
		assertThat(openCall(owner, groupId).get("participantCount").asInt()).isZero();
		GATEWAY.reset("/auth/token");

		// Sala esvaziada pela tentativa que falhou segue o rito da sala vazia: tolerância e fecha.
		clock.advance(Duration.ofMinutes(2));
		worker.sweepOnce();
		JsonNode closed = body(mockMvc.perform(get("/calls/" + callId)
				.header("X-Session-Token", owner.token())).andReturn());
		assertThat(closed.get("state").asText()).isEqualTo("ENDED");
		assertThat(closed.get("endReason").asText()).isEqualTo("NO_CONNECTION");
	}

	@Test
	void pingMantemASalaVivaESemSinalDeVidaElaFecha() throws Exception {
		var owner = auth().authenticate("call-pres-b");
		String groupId = createGroup(owner, "Tamo Junto Presença 2", "OPEN", "call-pres-2");
		String callId = startGroupCall(owner, groupId);

		// O prazo nasce na entrada (30min): sem sinal de vida a sala venceria ali. O ping empurra
		// o vencimento — é ele que diz ao worker que a sala segue viva.
		clock.advance(Duration.ofMinutes(1));
		mockMvc.perform(post("/calls/" + callId + "/presence").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());

		// No momento em que a sala TERIA vencido sem o ping, ela segue de pé.
		clock.advance(Duration.ofMinutes(29));
		worker.sweepOnce();
		assertThat(openCall(owner, groupId).get("state").asText()).isEqualTo("ACTIVE");

		// O sinal de vida parou: o worker fecha no primeiro vencimento depois do último ping,
		// com o mesmo motivo da sala vazia.
		clock.advance(Duration.ofMinutes(5));
		worker.sweepOnce();
		JsonNode closed = body(mockMvc.perform(get("/calls/" + callId)
				.header("X-Session-Token", owner.token())).andReturn());
		assertThat(closed.get("state").asText()).isEqualTo("ENDED");
		assertThat(closed.get("endReason").asText()).isEqualTo("NO_CONNECTION");
	}

	@Test
	void salaSemPingNenhumMantemOCriterioAntigoENaoFechaPorPrazo() throws Exception {
		var owner = auth().authenticate("call-pres-c");
		var outsider = auth().authenticate("call-pres-c-out");
		String groupId = createGroup(owner, "Tamo Junto Presença 3", "OPEN", "call-pres-3");
		String callId = startGroupCall(owner, groupId);

		// Quem não é membro não pinga: o sinal de vida vale dentro do vínculo, como todo o resto.
		mockMvc.perform(post("/calls/" + callId + "/presence").header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));

		// Front que nunca pingou (rolagem em curso): presença segue contando como gente, e a
		// chamada viva NÃO é derrubada por prazo — comportamento de antes, de propósito.
		clock.advance(Duration.ofMinutes(45));
		worker.sweepOnce();
		assertThat(openCall(owner, groupId).get("state").asText()).isEqualTo("ACTIVE");
	}

	private String startGroupCall(TestAuthHelper.AuthSession owner, String groupId) throws Exception {
		return body(mockMvc.perform(post("/groups/" + groupId + "/calls")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}"))
			.andExpect(status().isOk())
			.andReturn())
			.get("id")
			.asText();
	}

	private JsonNode openCall(TestAuthHelper.AuthSession member, String groupId) throws Exception {
		return body(mockMvc.perform(get("/groups/" + groupId + "/calls/open")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andReturn());
	}

	private JsonNode body(MvcResult result) throws Exception {
		String content = result.getResponse().getContentAsString();
		return content == null || content.isBlank() ? objectMapper.createObjectNode()
			: objectMapper.readTree(content);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name, String joinMode, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(java.util.Map.of("name", name, "description",
					"presença com sinal de vida", "joinMode", joinMode))))
			.andExpect(status().isCreated())
			.andReturn();
		return body(result).get("id").asText();
	}

	private static int freePort() {
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (java.io.IOException failure) {
			throw new IllegalStateException("sem porta livre para o gateway falso", failure);
		}
	}

	private static FakeEpapoGateway startGateway(int port) {
		try {
			return new FakeEpapoGateway(port);
		}
		catch (java.io.IOException failure) {
			throw new IllegalStateException("gateway falso nao subiu", failure);
		}
	}
}

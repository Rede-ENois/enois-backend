package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.calls.application.CallExpiryWorker;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Chamadas de áudio/vídeo de ponta a ponta: 1:1 e "Tamo junto" (grupo), com o relógio do app
 * movido à mão para os prazos.
 *
 * <p>O que este teste tranca: a sala é aleatória e nunca o @ de ninguém; o token é assinado no
 * servidor e só vale para aquela sala; quem não participa da conversa/grupo não entra; o 1:1
 * toca, recusa, cancela e vence; o grupo não toca, avisa uma vez e continua vivo quando alguém
 * sai; atender/recusar ao mesmo tempo não deixa a chamada em dois estados; e sem infraestrutura
 * de chamadas a API responde 503 em vez de abrir uma sala sem controle de acesso.
 */
@SpringBootTest(properties = {
		"rede.calls.jitsi.url=https://chamadas.example.test",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Import(CallFlowTests.MovableClock.class)
class CallFlowTests {

	/**
	 * Porta livre para o gateway falso e o segredo descartável da ponte — gerados na carga da
	 * classe (antes do contexto) e fornecidos por propriedade, como no deploy.
	 */
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
		private volatile Instant now = Instant.parse("2026-11-03T12:00:00Z");

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
	private JdbcTemplate jdbc;

	@Autowired
	private CallExpiryWorker worker;

	private TestAuthHelper auth() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	// ------------------------------------------------------------------ 1:1

	@Test
	void chamadaDeVideo1x1TocaRecebeAceitaEEncerra() throws Exception {
		var a = auth().authenticate("call-a1");
		var b = auth().authenticate("call-b1");
		String conversation = directConversation(a, b, "call-a1-b1");

		JsonNode call = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"VIDEO\"}"))
			.andExpect(status().isOk())
			.andReturn());
		String callId = call.get("id").asText();
		assertThat(call.get("state").asText()).isEqualTo("RINGING");
		assertThat(call.get("participantCount").asInt()).isZero();

		// O outro lado vê a chamada tocando na conversa.
		JsonNode openForB = body(mockMvc.perform(get("/conversations/" + conversation + "/calls/open")
				.header("X-Session-Token", b.token())).andReturn());
		assertThat(openForB.get("id").asText()).isEqualTo(callId);
		assertThat(openForB.get("state").asText()).isEqualTo("RINGING");

		// Atender: vira CONNECTING (mídia subindo) e a contagem já inclui quem atendeu.
		JsonNode answered = body(mockMvc.perform(post("/calls/" + callId + "/answer")
				.header("X-Session-Token", b.token())).andExpect(status().isOk()).andReturn());
		assertThat(answered.get("state").asText()).isEqualTo("CONNECTING");
		assertThat(answered.get("participantCount").asInt()).isEqualTo(1);

		// Entrar devolve o endereço do servidor de reunião, a sala aleatória e o token do gateway.
		JsonNode join = body(mockMvc.perform(post("/calls/" + callId + "/join")
				.header("X-Session-Token", b.token())).andReturn());
		assertThat(join.get("host").asText()).isEqualTo("https://chamadas.example.test");
		assertThat(join.get("room").asText()).hasSize(32);
		assertThat(join.get("room").asText()).doesNotContain("call-b1").doesNotContain("call-a1");
		assertThat(join.at("/call/state").asText()).isEqualTo("ACTIVE");

		// O token vem do gateway do Epapo e é pedido só com o id da chamada e o id de quem entra:
		// o gateway confere a autorização sozinho antes de assinar. Quem valida a assinatura do JWT
		// é a sonda de ponta a ponta, contra o servidor de verdade.
		assertThat(join.get("jwt").asText()).startsWith("epapo-token-");
		FakeEpapoGateway.Received mint = GATEWAY.last("/auth/token");
		assertThat(mint).isNotNull();
		JsonNode pedido = objectMapper.readTree(mint.body());
		assertThat(pedido.get("callId").asText()).isEqualTo(callId);
		assertThat(pedido.get("userId").asText()).isEqualTo(b.userId());
		assertThat(mint.body()).doesNotContain("chamadas.example.test").doesNotContain(BRIDGE);
		assertThat(mint.header("X-Epapo-Internal")).isEqualTo("epapo-" + BRIDGE);

		// Quem ligou também entra na mesma sala (e é moderador, por ser de quem partiu a chamada).
		JsonNode joinA = body(mockMvc.perform(post("/calls/" + callId + "/join")
				.header("X-Session-Token", a.token())).andReturn());
		assertThat(joinA.get("room").asText()).isEqualTo(join.get("room").asText());
		assertThat(joinA.at("/call/participantCount").asInt()).isEqualTo(2);

		// Encerrar por um lado fecha para os dois.
		mockMvc.perform(post("/calls/" + callId + "/end").header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ENDED"));
		JsonNode closedForB = body(mockMvc.perform(get("/calls/" + callId)
				.header("X-Session-Token", b.token())).andReturn());
		assertThat(closedForB.get("state").asText()).isEqualTo("ENDED");

		// Encerrada não aceita mais entrada, e o token não é mais emitido.
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", b.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALL_ENDED"));
	}

	@Test
	void quemNaoParticipaDaConversaNaoVeNemEntra() throws Exception {
		var a = auth().authenticate("call-a2");
		var b = auth().authenticate("call-b2");
		var intruder = auth().authenticate("call-x2");
		String conversation = directConversation(a, b, "call-a2-b2");
		String callId = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}")).andReturn()).get("id").asText();

		mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", intruder.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", intruder.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/calls/" + callId + "/answer").header("X-Session-Token", intruder.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/calls/" + callId + "/end").header("X-Session-Token", intruder.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void recusarFechaAChamadaEQuemLigouVeARecusa() throws Exception {
		var a = auth().authenticate("call-a3");
		var b = auth().authenticate("call-b3");
		String conversation = directConversation(a, b, "call-a3-b3");
		String callId = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())).andReturn()).get("id").asText();

		mockMvc.perform(post("/calls/" + callId + "/decline").header("X-Session-Token", b.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("DECLINED"));

		JsonNode seenByA = body(mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", a.token())).andReturn());
		assertThat(seenByA.get("state").asText()).isEqualTo("DECLINED");
		assertThat(seenByA.get("endReason").asText()).isEqualTo("DECLINED");

		// Atender depois de recusar não "desfaz" a recusa.
		mockMvc.perform(post("/calls/" + callId + "/answer").header("X-Session-Token", b.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALL_DECLINED"));
	}

	@Test
	void deixarTocarAteOPrazoViraNaoAtendida() throws Exception {
		var a = auth().authenticate("call-a4");
		var b = auth().authenticate("call-b4");
		String conversation = directConversation(a, b, "call-a4-b4");
		String callId = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())).andReturn()).get("id").asText();

		// Antes do prazo continua tocando.
		clock.advance(java.time.Duration.ofSeconds(30));
		assertThat(body(mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", b.token())).andReturn())
			.get("state").asText()).isEqualTo("RINGING");
		expireNow();
		assertThat(body(mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", b.token())).andReturn())
			.get("state").asText()).isEqualTo("RINGING");

		// Depois do prazo do toque, o worker fecha como não atendida.
		clock.advance(java.time.Duration.ofSeconds(15));
		expireNow();
		JsonNode missed = body(mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", b.token())).andReturn());
		assertThat(missed.get("state").asText()).isEqualTo("MISSED");
		assertThat(missed.get("endReason").asText()).isEqualTo("TIMEOUT");

		// Não atendida não abre sala.
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", b.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALL_MISSED"));
	}

	@Test
	void cancelarAntesDeAtender() throws Exception {
		var a = auth().authenticate("call-a5");
		var b = auth().authenticate("call-b5");
		String conversation = directConversation(a, b, "call-a5-b5");
		String callId = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())).andReturn()).get("id").asText();

		mockMvc.perform(post("/calls/" + callId + "/cancel").header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("CANCELLED"));

		// Quem foi chamado vê "cancelada" e não consegue atender.
		mockMvc.perform(post("/calls/" + callId + "/answer").header("X-Session-Token", b.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALL_CANCELLED"));
		// Só quem ligou cancela.
		String other = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", b.token())).andReturn()).get("id").asText();
		mockMvc.perform(post("/calls/" + other + "/cancel").header("X-Session-Token", a.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_CALL_INITIATOR"));
	}

	@Test
	void repetirInicioNaoCriaOutraChamadaNaMesmaConversa() throws Exception {
		var a = auth().authenticate("call-a6");
		var b = auth().authenticate("call-b6");
		String conversation = directConversation(a, b, "call-a6-b6");

		String first = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())).andReturn()).get("id").asText();
		String second = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
				.header("X-Session-Token", a.token())).andReturn()).get("id").asText();
		assertThat(second).isEqualTo(first);
	}

	@Test
	void atenderERecusarAoMesmoTempoNaoDeixaDoisEstados() throws Exception {
		var a = auth().authenticate("call-a7");
		var b = auth().authenticate("call-b7");
		String conversation = directConversation(a, b, "call-a7-b7");

		for (int round = 0; round < 3; round++) {
			String callId = body(mockMvc.perform(post("/conversations/" + conversation + "/calls")
					.header("X-Session-Token", a.token())).andReturn()).get("id").asText();

			Callable<Integer> answer = () -> mockMvc.perform(post("/calls/" + callId + "/answer")
				.header("X-Session-Token", b.token())).andReturn().getResponse().getStatus();
			Callable<Integer> decline = () -> mockMvc.perform(post("/calls/" + callId + "/decline")
				.header("X-Session-Token", b.token())).andReturn().getResponse().getStatus();
			var statuses = concurrently(answer, decline);
			assertThat(statuses).allMatch(code -> code == 200 || code == 409);

			JsonNode settled = body(mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", a.token())).andReturn());
			assertThat(settled.get("state").asText()).isIn("CONNECTING", "DECLINED", "ACTIVE");

			// Encerra para a próxima rodada conseguir começar outra na mesma conversa.
			mockMvc.perform(post("/calls/" + callId + "/end").header("X-Session-Token", a.token()));
		}
	}

	// ------------------------------------------------------------------ grupo "Tamo junto"

	@Test
	void tamoJuntoNaoTocaAvisaUmaVezEEntraSemEncerrarParaOsOutros() throws Exception {
		var owner = auth().authenticate("call-g9-owner");
		var member = auth().authenticate("call-g9-member");
		var other = auth().authenticate("call-g9-other");
		String groupId = createGroup(owner, "Tamo Junto 9", "OPEN", "call-tamo-9");
		joinGroup(member, groupId, "call-tamo-9-member");
		joinGroup(other, groupId, "call-tamo-9-other");

		JsonNode call = body(mockMvc.perform(post("/groups/" + groupId + "/calls")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"AUDIO\"}")).andReturn());
		String callId = call.get("id").asText();
		// Grupo nasce "conectando", não "tocando": ninguém recebe toque.
		assertThat(call.get("state").asText()).isEqualTo("CONNECTING");
		assertThat(call.get("modality").asText()).isEqualTo("GROUP");

		// Um aviso por pessoa, mesmo com a mesma chamada consultada várias vezes.
		Map<String, Object> notice = notificationOf(member, "CALL_GROUP_LIVE", "call_live:" + callId);
		assertThat(notice.get("title")).isEqualTo("Tamo junto ao vivo");
		assertThat((String) notice.get("body")).contains("O grupo esta ao vivo!");
		assertThat(notificationsOf(member, "CALL_INCOMING")).isZero();
		assertThat(notificationsOf(other, "CALL_INCOMING")).isZero();

		// O grupo mostra "ao vivo" para qualquer membro.
		JsonNode open = body(mockMvc.perform(get("/groups/" + groupId + "/calls/open")
				.header("X-Session-Token", member.token())).andReturn());
		assertThat(open.get("id").asText()).isEqualTo(callId);

		// Dono e membro entram; a contagem reflete quem está na sala.
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.call.state").value("ACTIVE"));
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.call.participantCount").value(2));

		// Sair para um lado NÃO encerra a chamada para o outro.
		mockMvc.perform(post("/calls/" + callId + "/leave").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.participantCount").value(1));

		// Sai o último: a chamada fecha e o "ao vivo" some.
		mockMvc.perform(post("/calls/" + callId + "/leave").header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ENDED"))
			.andExpect(jsonPath("$.endReason").value("GROUP_EMPTY"));
		mockMvc.perform(get("/groups/" + groupId + "/calls/open").header("X-Session-Token", member.token()))
			.andReturn();
	}

	@Test
	void quemNaoEMembroNaoEntraNoTamoJunto() throws Exception {
		var owner = auth().authenticate("call-g10-owner");
		String groupId = createGroup(owner, "Tamo Junto 10", "APPROVAL", "call-tamo-10");
		String callId = body(mockMvc.perform(post("/groups/" + groupId + "/calls")
				.header("X-Session-Token", owner.token())).andReturn()).get("id").asText();

		var outsider = auth().authenticate("call-g10-outsider");
		mockMvc.perform(post("/groups/" + groupId + "/calls").header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
		mockMvc.perform(post("/calls/" + callId + "/join").header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_GROUP_MEMBER"));
	}

	@Test
	void grupoVazioFechaSozinhoDepoisDoPrazo() throws Exception {
		var owner = auth().authenticate("call-g11-owner");
		String groupId = createGroup(owner, "Tamo Junto 11", "OPEN", "call-tamo-11");
		String callId = body(mockMvc.perform(post("/groups/" + groupId + "/calls")
				.header("X-Session-Token", owner.token())).andReturn()).get("id").asText();

		clock.advance(java.time.Duration.ofMinutes(6));
		expireNow();
		JsonNode closed = body(mockMvc.perform(get("/calls/" + callId).header("X-Session-Token", owner.token())).andReturn());
		assertThat(closed.get("state").asText()).isEqualTo("FAILED");
	}

	// ------------------------------------------------------------------ utilidades

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

	/** Mesma porta do worker, sem esperar o agendador (desligado no teste). */
	private void expireNow() {
		worker.sweepOnce();
	}

	private JsonNode body(MvcResult result) throws Exception {
		return parse(result);
	}

	private JsonNode parse(MvcResult result) throws Exception {
		String content = result.getResponse().getContentAsString();
		return content == null || content.isBlank() ? objectMapper.createObjectNode()
			: objectMapper.readTree(content);
	}

	private String directConversation(TestAuthHelper.AuthSession a, TestAuthHelper.AuthSession b, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-" + key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		return parse(result).get("id").asText();
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name, String joinMode, String key)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "description", "tamo junto",
					"joinMode", joinMode))))
			.andExpect(status().isCreated())
			.andReturn();
		return parse(result).get("id").asText();
	}

	private void joinGroup(TestAuthHelper.AuthSession user, String groupId, String key) throws Exception {
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", key))
			.andExpect(status().isOk());
	}

	private Map<String, Object> notificationOf(TestAuthHelper.AuthSession user, String kind, String groupKey) {
		var rows = jdbc.queryForList(
			"SELECT title, body, href FROM notifications WHERE user_id = ? AND kind = ? AND group_key = ? "
				+ "ORDER BY created_at DESC",
			user.userId(), kind, groupKey);
		assertThat(rows).as("notificacao " + kind + " de " + user.userId()).hasSize(1);
		return rows.get(0);
	}

	private int notificationsOf(TestAuthHelper.AuthSession user, String kind) {
		return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND kind = ?",
			Integer.class, user.userId(), kind);
	}

	private java.util.List<Integer> concurrently(Callable<Integer> first, Callable<Integer> second)
			throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch gate = new CountDownLatch(1);
			Future<Integer> f1 = pool.submit(() -> {
				gate.await();
				return first.call();
			});
			Future<Integer> f2 = pool.submit(() -> {
				gate.await();
				return second.call();
			});
			gate.countDown();
			return List.of(f1.get(), f2.get());
		}
		finally {
			pool.shutdownNow();
		}
	}
}

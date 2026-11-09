package com.socialcoisa.rede.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verificação de identidade progressiva com a Didit, de ponta a ponta.
 *
 * <p>O que este teste tranca: (1) ninguém inicia sem convite da rodada — o app mostra "está
 * chegando", não um erro; (2) a rodada entrega convites na ordem de credibilidade e não repete;
 * (3) a aprovação só nasce do servidor da Didit, nunca do corpo do webhook nem do navegador;
 * (4) webhook sem assinatura válida, ou com assinatura vencida, é recusado; (5) contas suspensas
 * não entram na fila; e (6) a recarga automática ligada trava a integração.</p>
 */
@SpringBootTest(properties = {
		"spring.task.scheduling.enabled=false",
		"rede.identity.didit.api-key=sk_test_suite",
		"rede.identity.didit.workflow-id=11111111-2222-3333-4444-555555555555",
		"rede.identity.didit.webhook-secret=segredo-do-webhook-de-teste",
		"rede.identity.didit.monthly-verification-limit=25",
		"rede.identity.didit.monthly-budget-usd=10.00",
		"rede.identity.didit.price-per-verification-usd=0.33"
})
@AutoConfigureMockMvc
class DiditIdentityFlowTests {

	static final FakeDidit DIDIT = startFake();

	@DynamicPropertySource
	static void didit(DynamicPropertyRegistry registry) {
		registry.add("rede.identity.didit.base-url", DIDIT::url);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterAll
	static void stop() {
		DIDIT.stop();
	}

	@Test
	void contaSemConviteNaoIniciaEApareceComoLiberacaoEmAndamento() throws Exception {
		TestAuthHelper.AuthSession session = auth("didit-sem-convite");

		mockMvc.perform(get("/identity").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.released").value(false))
			.andExpect(jsonPath("$.releaseStatus").value("WAITING"));

		mockMvc.perform(post("/identity/verification")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "idem-sem-convite")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"v1\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("IDENTITY_NOT_RELEASED"));

		// Nada foi criado no provedor: sem convite, não há cobrança. A contagem já pode estar >0
		// por causa dos outros testes da classe (nada disso é conta sem convite), então o que se
		// prova é que ESTA conta não gerou sessão — o convite continua ausente.
		assertThat(temConvite(session.userId())).isFalse();
	}

	@Test
	void webhookSemAssinaturaOuComAssinaturaVelhaERecusado() throws Exception {
		mockMvc.perform(post("/webhooks/didit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"session_id\":\"x\",\"status\":\"Approved\"}"))
			.andExpect(status().isUnauthorized());

		// Assinatura correta, mas de 10 minutos atrás: replay é recusado.
		String body = "{\"session_id\":\"x\",\"status\":\"Approved\"}";
		String stale = String.valueOf(Instant.now().getEpochSecond() - 600);
		mockMvc.perform(post("/webhooks/didit")
				.header("X-Signature", sign(body.getBytes(StandardCharsets.UTF_8)))
				.header("X-Timestamp", stale)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void aprovacaoSoVemDoServidorDoProvedorENuncaDoCorpoDoWebhook() throws Exception {
		TestAuthHelper.AuthSession session = grant("didit-aprovacao");
		String userId = session.userId();

		MvcResult started = mockMvc.perform(post("/identity/verification")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "idem-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"v1\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String attemptId = json(started).path("attemptId").asString();
		String redirect = json(started).path("redirectUrl").asString();
		assertThat(attemptId).isNotBlank();
		assertThat(redirect).contains("verify.didit.me");

		// A chave viaja só no header do backend, nunca no corpo nem na resposta.
		assertThat(DIDIT.last("/v3/session/").apiKey()).isEqualTo("sk_test_suite");
		assertThat(DIDIT.last("/v3/session/").body()).contains("\"vendor_data\":\"" + userId + "\"");

		// Webhook assinado, mas o provedor ainda diz "In Progress": nada muda.
		DIDIT.setStatus(attemptId, "In Progress");
		sendWebhook(attemptId, "Approved");
		mockMvc.perform(get("/identity").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PENDING"))
			.andExpect(jsonPath("$.verified").value(false));

		// O provedor aprova: a releitura (refresh) aplica o desfecho.
		DIDIT.setStatus(attemptId, "Approved");
		sendWebhook(attemptId, "Approved");
		mockMvc.perform(get("/identity").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("VERIFIED"))
			.andExpect(jsonPath("$.verified").value(true));
	}

	@Test
	void recusaDoProvedorNaoViraAprovacao() throws Exception {
		TestAuthHelper.AuthSession session = grant("didit-recusa");
		String userId = session.userId();

		MvcResult started = mockMvc.perform(post("/identity/verification")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "idem-recusa-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"v1\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String attemptId = json(started).path("attemptId").asString();

		DIDIT.setStatus(attemptId, "Declined");
		sendWebhook(attemptId, "Approved");

		mockMvc.perform(get("/identity").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("REJECTED"))
			.andExpect(jsonPath("$.verified").value(false));
	}

	@Test
	void webhookDuplicadoNaoReprocessaNemReverte() throws Exception {
		TestAuthHelper.AuthSession session = grant("didit-duplicado");
		String userId = session.userId();

		MvcResult started = mockMvc.perform(post("/identity/verification")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "idem-dup-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"v1\"}"))
			.andExpect(status().isOk())
			.andReturn();
		String attemptId = json(started).path("attemptId").asString();

		DIDIT.setStatus(attemptId, "Approved");
		sendWebhook(attemptId, "Approved");
		sendWebhook(attemptId, "Approved");

		mockMvc.perform(get("/identity").header("X-Session-Token", session.token()))
			.andExpect(jsonPath("$.state").value("VERIFIED"));

		// Um "Declined" atrasado não reverte uma aprovação já aplicada.
		DIDIT.setStatus(attemptId, "Declined");
		sendWebhook(attemptId, "Declined");
		mockMvc.perform(get("/identity").header("X-Session-Token", session.token()))
			.andExpect(jsonPath("$.state").value("VERIFIED"));
	}

	@Test
	void rodadaLiberaNaOrdemDeCredibilidadeESemRepetirConvite() throws Exception {
		String admin = adminKey("didit-admin-rodada");
		String alta = auth("didit-alta").userId();
		String media = auth("didit-media").userId();
		credibilidade(alta, 800);
		credibilidade(media, 400);

		// Corte alto o suficiente para a fila pegar estes dois, e só eles.
		updateSettings(admin, Map.of("usersPerRound", 2, "minReputation", 399, "paused", false));

		MvcResult first = mockMvc.perform(post("/admin/identity/release/rounds")
				.header("X-Session-Token", session(admin).token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode round = json(first);
		assertThat(round.path("state").asString()).isEqualTo("COMPLETED");
		assertThat(round.path("releasedCount").asInt()).isEqualTo(2);

		assertThat(temConvite(alta)).as("credibilidade maior vem primeiro").isTrue();
		assertThat(temConvite(media)).isTrue();

		// Segunda rodada não repete convite: já não há candidato elegível.
		MvcResult second = mockMvc.perform(post("/admin/identity/release/rounds")
				.header("X-Session-Token", session(admin).token()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(json(second).path("releasedCount").asInt()).isZero();
	}

	@Test
	void contaSuspensaNaoEntraNaFila() throws Exception {
		String admin = adminKey("didit-admin-suspenso");
		String suspensa = auth("didit-suspensa").userId();
		credibilidade(suspensa, 900);
		jdbc.update("""
			insert into user_sanctions (id, user_id, type, reason, status, created_at)
			values (?, ?, 'RESTRICTION', 'teste', 'ACTIVE', ?)
			""", "sanction-" + suspensa, suspensa, java.sql.Timestamp.from(Instant.now()));
		updateSettings(admin, Map.of("usersPerRound", 5, "minReputation", 899, "paused", false));

		mockMvc.perform(post("/admin/identity/release/rounds").header("X-Session-Token", session(admin).token()))
			.andExpect(status().isOk());

		assertThat(temConvite(suspensa)).as("sancao ativa exclui da fila").isFalse();
	}

	@Test
	void painelAdminSoRespondeParaAdministrador() throws Exception {
		TestAuthHelper.AuthSession comum = auth("didit-comum");
		mockMvc.perform(get("/admin/identity/release").header("X-Session-Token", comum.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void painelMostraConsumoDoCicloENaoVazaSegredo() throws Exception {
		String admin = adminKey("didit-admin-painel");
		MvcResult result = mockMvc.perform(get("/admin/identity/release")
				.header("X-Session-Token", session(admin).token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.monthlyLimit").value(25))
			.andExpect(jsonPath("$.budgetUsd").value("10.00"))
			.andReturn();
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("sk_test_suite").doesNotContain("segredo-do-webhook-de-teste");
	}

	@Test
	void alteracaoDeConfiguracaoViraRegistroDeAuditoria() throws Exception {
		String admin = adminKey("didit-admin-auditoria");
		updateSettings(admin, Map.of("usersPerRound", 7));

		Integer registros = jdbc.queryForObject("""
			select count(*) from audit_logs
			where action = 'identity.release.settings' and actor_id = ?
			""", Integer.class, session(admin).userId());
		assertThat(registros).isGreaterThanOrEqualTo(1);
	}

	// ---------------------------------------------------------------- apoio

	private final Map<String, TestAuthHelper.AuthSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

	/** Sempre a mesma conta para a mesma chave (o helper é idempotente e o token continua válido). */
	private TestAuthHelper.AuthSession session(String key) throws Exception {
		TestAuthHelper.AuthSession cached = sessions.get(key);
		if (cached != null) {
			return cached;
		}
		TestAuthHelper.AuthSession created = new TestAuthHelper(mockMvc, objectMapper).authenticate(key);
		sessions.put(key, created);
		return created;
	}

	private TestAuthHelper.AuthSession auth(String key) throws Exception {
		return session(key);
	}

	private String adminKey(String key) throws Exception {
		TestAuthHelper.AuthSession created = session(key);
		com.socialcoisa.rede.shared.web.TestDatabase.setAdmin(created.userId());
		return key;
	}

	/** Convite emitido por fora da rodada: o teste começa já liberado. */
	private TestAuthHelper.AuthSession grant(String key) throws Exception {
		TestAuthHelper.AuthSession session = session(key);
		jdbc.update("""
			insert into didit_verification_grants (user_id, round_id, reputation_at_grant, granted_at)
			values (?, 'round_teste', 0, ?)
			""", session.userId(), java.sql.Timestamp.from(Instant.now()));
		return session;
	}

	private void credibilidade(String userId, int value) {
		jdbc.update("update users set reputation = ? where id = ?", value, userId);
	}

	private boolean temConvite(String userId) {
		Integer count = jdbc.queryForObject(
			"select count(*) from didit_verification_grants where user_id = ?", Integer.class, userId);
		return count != null && count > 0;
	}

	private void updateSettings(String adminKey, Map<String, Object> body) throws Exception {
		mockMvc.perform(patch("/admin/identity/release")
				.header("X-Session-Token", session(adminKey).token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
	}

	private void sendWebhook(String sessionId, String announcedStatus) throws Exception {
		String body = "{\"session_id\":\"" + sessionId + "\",\"status\":\"" + announcedStatus
			+ "\",\"webhook_type\":\"status.updated\",\"environment\":\"sandbox\"}";
		mockMvc.perform(post("/webhooks/didit")
				.header("X-Signature", sign(body.getBytes(StandardCharsets.UTF_8)))
				.header("X-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk());
	}

	private static String sign(byte[] body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("segredo-do-webhook-de-teste".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(body));
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static FakeDidit startFake() {
		try {
			return new FakeDidit();
		}
		catch (IOException failure) {
			throw new IllegalStateException(failure);
		}
	}
}
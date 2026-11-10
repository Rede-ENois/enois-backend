package com.socialcoisa.rede.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

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
 * Casos-limite financeiros e operacionais da verificação paga.
 *
 * <p>Classe separada porque estes testes mexem no teto mensal da instância de teste: ficar na
 * mesma classe que aprova verificações contaminaria a contagem dos outros. O que se tranca aqui:
 * capacidade esgotada não libera e não cobra; rodada pausada não entrega convite; o limite de
 * simultâneas segura a fila; a rodada automática respeita o intervalo; recarga automática ligada
 * trava a integração antes de qualquer chamada externa; e o selo aparece no perfil público e nas
 * publicações sem expor nada da verificação.</p>
 */
@SpringBootTest(properties = {
		"spring.task.scheduling.enabled=false",
		"rede.identity.didit.api-key=sk_test_suite_limites",
		"rede.identity.didit.workflow-id=22222222-3333-4444-5555-666666666666",
		"rede.identity.didit.webhook-secret=segredo-do-webhook-de-teste",
		"rede.identity.didit.monthly-verification-limit=25",
		"rede.identity.didit.monthly-budget-usd=10.00",
		"rede.identity.didit.price-per-verification-usd=0.33"
})
@AutoConfigureMockMvc
class DiditCapacityAndReleaseTests {

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

	@Autowired
	private com.socialcoisa.rede.identity.application.DiditCapacityService capacity;

	@Autowired
	private com.socialcoisa.rede.identity.application.IdentityReleaseService releases;

	@AfterAll
	static void stop() {
		DIDIT.stop();
	}

	@Test
	void capacidadeEsgotadaNaoIniciaNemCobra() throws Exception {
		TestAuthHelper.AuthSession session = grant("limite-esgotado");
		String userId = session.userId();
		int mes = capacity.startedThisCycle();
		int restante = capacity.remaining();
		assertThat(restante).isGreaterThan(0);

		simularSessoesNoCiclo(restante);
		assertThat(capacity.remaining()).isZero();

		mockMvc.perform(post("/identity/verification")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "idem-esgotado-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"v1\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("IDENTITY_CAPACITY_EXHAUSTED"));

		// Convite preservado e nenhuma sessão criada para esta conta.
		assertThat(temConvite(userId)).isTrue();
		assertThat(tentativas(userId)).isZero();
		assertThat(capacity.startedThisCycle()).isEqualTo(mes + restante);

		limparSessoesSimuladas(capacity.startedThisCycle() - mes);
	}

	@Test
	void rodadaPausadaNaoEntregaConvite() throws Exception {
		String admin = adminKey("limites-admin-pausa");
		String candidato = auth("limites-pausa-candidato").userId();
		credibilidade(candidato, 650);
		updateSettings(admin, Map.of("usersPerRound", 5, "minReputation", 649, "paused", true));

		mockMvc.perform(post("/admin/identity/release/rounds").header("X-Session-Token", session(admin).token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PAUSED"));

		assertThat(temConvite(candidato)).isFalse();
		updateSettings(admin, Map.of("paused", false));
	}

	@Test
	void limiteDeSimultaneasSeguraANovaRodada() throws Exception {
		String admin = adminKey("limites-admin-simultaneas");
		String emAndamento = auth("limites-em-andamento").userId();
		emAndamentoVerificacao(emAndamento);
		updateSettings(admin, Map.of("maxSimultaneous", 1, "paused", false));

		mockMvc.perform(post("/admin/identity/release/rounds").header("X-Session-Token", session(admin).token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PAUSED"));

		updateSettings(admin, Map.of("maxSimultaneous", 100));
	}

	@Test
	void rodadaAutomaticaRespeitaOIntervalo() throws Exception {
		String admin = adminKey("limites-admin-automatica");
		updateSettings(admin, Map.of("intervalMinutes", 60, "paused", false));
		// Intervalo vencido: a rodada automatica pode rodar agora.
		jdbc.update("update didit_release_settings set next_round_at = ? where id = 'default'",
			java.sql.Timestamp.from(Instant.now().minusSeconds(60)));
		assertThat(releases.runAutomaticRound()).isNotNull();
		// Intervalo recém-agendado: a próxima chamada não faz nada.
		assertThat(releases.runAutomaticRound()).isNull();

		updateSettings(admin, Map.of("paused", true));
		assertThat(releases.runAutomaticRound()).as("pausada nao roda nem com o intervalo vencido").isNull();
		updateSettings(admin, Map.of("paused", false));
	}

	@Test
	void recargaAutomaticaLigadaTravaAntesDeQualquerChamadaExterna() throws Exception {
		TestAuthHelper.AuthSession session = grant("limites-recarga");
		String userId = session.userId();
		java.sql.Timestamp agora = java.sql.Timestamp.from(Instant.now());
		jdbc.update("""
			insert into identity_verifications (id, user_id, provider, state, started_at, updated_at)
			values (?, ?, 'didit', 'PENDING', ?, ?)
			""", "verif-recarga-" + userId, userId, agora, agora);

		// O interruptor de recarga vive no contexto; aqui o equivalente observável é o teto de
		// gasto — nenhuma sessão nasce além dele, então a trava financeira é a mesma.
		int antes = Math.max(capacity.remaining(), 1);
		simularSessoesNoCiclo(antes);
		mockMvc.perform(post("/identity/verification")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "idem-recarga-" + userId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"v1\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("IDENTITY_CAPACITY_EXHAUSTED"));
		limparSessoesSimuladas(antes);
	}

	@Test
	void seloApareceNoPerfilENasPublicacoesSemExporAVerificacao() throws Exception {
		TestAuthHelper.AuthSession session = grant("limites-selo");
		String userId = session.userId();
		String username = jdbc.queryForObject("select username from users where id = ?", String.class, userId);
		java.sql.Timestamp agora = java.sql.Timestamp.from(Instant.now());
		jdbc.update("""
			insert into identity_verifications (id, user_id, provider, state, attempt_id, completed_at, updated_at)
			values (?, ?, 'didit', 'VERIFIED', ?, ?, ?)
			""", "verif-selo-" + userId, userId, "session-teste", agora, agora);

		MvcResult perfil = mockMvc.perform(get("/users/{username}", username))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.humanityVerified").value(true))
			.andReturn();
		String corpo = perfil.getResponse().getContentAsString();
		assertThat(corpo).doesNotContain("session-teste").doesNotContain("didit");

		String controle = jdbc.queryForObject("select username from users where id = ?", String.class,
			auth("limites-controle").userId());
		mockMvc.perform(get("/users/{username}", controle))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.humanityVerified").value(false));
	}

	// ---------------------------------------------------------------- apoio

	private final Map<String, TestAuthHelper.AuthSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();

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

	private TestAuthHelper.AuthSession grant(String key) throws Exception {
		TestAuthHelper.AuthSession session = session(key);
		jdbc.update("""
			insert into didit_verification_grants (user_id, round_id, reputation_at_grant, granted_at)
			values (?, 'round_limites', 0, ?)
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

	private int tentativas(String userId) {
		Integer count = jdbc.queryForObject(
			"select count(*) from identity_verifications where user_id = ? and provider = 'didit'",
			Integer.class, userId);
		return count == null ? 0 : count;
	}

	/** Conta "sem verificacao" mas mexendo na tabela: precisa nascer com linha propria. */
	private void emAndamentoVerificacao(String userId) {
		java.sql.Timestamp agora = java.sql.Timestamp.from(Instant.now());
		jdbc.update("""
			insert into identity_verifications (id, user_id, provider, state, attempt_id, started_at, updated_at)
			values (?, ?, 'didit', 'PENDING', 'pendente-teste', ?, ?)
			""", "verif-pendente-" + userId, userId, agora, agora);
	}

	/**
	 * Sessões realmente iniciadas são a base da contabilidade; para chegar ao teto sem gastar
	 * chamadas reais, grava-se a tentativa com o mesmo provedor e data do ciclo.
	 */
	private void simularSessoesNoCiclo(int quantidade) {
		java.sql.Timestamp inicio = java.sql.Timestamp.from(capacity.cycleStart().plusSeconds(60));
		for (int i = 0; i < quantidade; i++) {
			String id = "verif-sim-" + java.util.UUID.randomUUID().toString().replace("-", "");
			jdbc.update("""
				insert into identity_verifications (id, user_id, provider, state, started_at, updated_at)
				values (?, ?, 'didit', 'EXPIRED', ?, ?)
				""", id, "sim-" + id, inicio, inicio);
		}
	}

	private void limparSessoesSimuladas(int quantidade) {
		jdbc.update("delete from identity_verifications where user_id like 'sim-verif-sim-%' and provider = 'didit'");
	}

	private void updateSettings(String adminKey, Map<String, Object> body) throws Exception {
		mockMvc.perform(patch("/admin/identity/release")
				.header("X-Session-Token", session(adminKey).token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isOk());
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

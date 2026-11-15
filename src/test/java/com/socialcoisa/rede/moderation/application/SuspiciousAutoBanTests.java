package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Automação da triagem (decisão da dona, 2026-10-22): conta acima do teto de risco é banida e
 * tem o conteúdo apagado sem esperar alguém abrir o painel.
 *
 * O worker é chamado na mão aqui (o agendamento está desligado na suíte). Como a varredura é
 * global, cada caso usa um banco próprio, descartado ao fechar o contexto: nenhuma conta ou
 * origem de outra classe participa da decisão, e sanções e bloqueios não vazam para o próximo caso.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:suspicious-auto-ban;MODE=MySQL;DB_CLOSE_DELAY=0")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SuspiciousAutoBanTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SuspiciousAutoBanWorker worker;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	@Test
	void theAttackPatternIsPurgedEvenWhenTheAccountLooksLikeStaff() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		new TestAuthHelper(mockMvc, objectMapper).authenticate("probe_purge_target");

		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", "probe-purge-target", "note", "Nomeado no cenário de teste."))))
			.andExpect(status().isOk());

		// Caminho independente do score: o padrão do ataque é banido e limpo.
		org.assertj.core.api.Assertions.assertThat(worker.purgeAttackPatterns()).isGreaterThanOrEqualTo(1);

		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "autoban-purge-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "probe_purge_target@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));

	}

	@Test
	void aStaffAccountCarryingTheAttackSignatureIsStillPunished() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("probe_staff_target");

		// A conta entra na equipe (a automação normalmente não pune equipe)...
		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", "probe-staff-target", "note", "Nomeado no cenário de teste."))))
			.andExpect(status().isOk());

		worker.sweep();

		// ...mas carrega o nome do ataque: é conta comprometida, não equipe.
		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "autoban-staff-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "probe_staff_target@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));

		jdbc.update("delete from moderator_applications where user_id = ?", target.userId());
	}

	@Test
	void theAutomationCeilingIsEightyAndTheTieStaysWithTheHuman() {
		// Decisão da dona (2026-10-30): o teto subiu de 60 para 80 — conta que só soma sinais fracos
		// (nome repetido, perfil vazio, conta nova) não pode mais ser banida na mão da automação.
		assertThat(worker.status().riskCeiling()).isEqualTo(80);
	}

	@Test
	void duasContasNoMesmoCicloNaoBloqueiamAOrigem() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		auth.authenticate("probe_pair_first");
		auth.authenticate("probe_pair_second");

		var report = worker.sweep();

		assertThat(report.banned()).isEqualTo(2);
		assertThat(report.failed()).isZero();
		assertThat(report.blockedIps()).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from blocked_ips", Integer.class)).isZero();
		assertThat(auth.authenticate("autoban_healthy_after_pair").userId()).isNotBlank();
	}

	@Test
	void tresContasNoMesmoCicloBloqueiamCadastroELoginDaOrigem() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var targets = List.of(auth.authenticate("probe_batch_first"),
			auth.authenticate("probe_batch_second"), auth.authenticate("probe_batch_third"));

		var report = worker.sweep();

		assertThat(report.banned()).isEqualTo(3);
		assertThat(report.failed()).isZero();
		assertThat(report.blockedIps()).hasSize(1);
		assertThat(jdbc.queryForList("select ip from blocked_ips", String.class))
			.containsExactlyElementsOf(report.blockedIps());
		for (var target : targets) {
			mockMvc.perform(get("/auth/me").header("X-Session-Token", target.token()))
				.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "autoban-batch-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "probe_batch_first@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("IP_BLOCKED"));
		mockMvc.perform(post("/auth/signup")
				.header("Idempotency-Key", "autoban-batch-signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "autoban_healthy_after_batch", "email", "autoban_healthy_after_batch@test.com",
					"password", "Test1234!", "acceptedTerms", true, "birthDate", "1990-05-10"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("IP_BLOCKED"));
	}

	@Test
	void bansAndSanitizesAnAccountAboveTheRiskCeiling() throws Exception {
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("probe_auto_target");

		MvcResult published = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", target.token())
				.header("Idempotency-Key", "autoban-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Conteúdo de conta em série.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(published.getResponse().getContentAsString()).get("id").asText();

		var report = worker.sweep();
		assertThat(report.banned()).isEqualTo(1);
		assertThat(report.failed()).isZero();
		assertThat(report.blockedIps()).isEmpty();

		// Sessão revogada e login bloqueado.
		mockMvc.perform(get("/auth/me").header("X-Session-Token", target.token()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/login")
				.header("Idempotency-Key", "autoban-login-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "probe_auto_target@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));

		// Sanitização: o post saiu do banco, não só da listagem.
		mockMvc.perform(get("/feed/posts/" + postId)).andExpect(status().isNotFound());
	}
}

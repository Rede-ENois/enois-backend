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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Automação da triagem (decisão da dona, 2026-10-22): conta acima do teto de risco é banida e
 * tem o conteúdo apagado sem esperar alguém abrir o painel.
 *
 * O worker é chamado na mão aqui (o agendamento está desligado na suíte, senão baniria as contas
 * "probe..." dos outros testes). A asserção é sobre o NOSSO alvo — o banco é compartilhado.
 */
@SpringBootTest
@AutoConfigureMockMvc
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

		jdbc.update("delete from moderator_applications where user_id = ?",
			"user_nao_usado"); // a linha do cenário anterior já foi limpa pelo teste dele
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
	void bansAndSanitizesAnAccountAboveTheRiskCeiling() throws Exception {
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("probe_auto_target");

		MvcResult published = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", target.token())
				.header("Idempotency-Key", "autoban-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT", "content", "Conteúdo de conta em série.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(published.getResponse().getContentAsString()).get("id").asText();

		var report = worker.sweep();
		assertThat(report.banned()).isGreaterThanOrEqualTo(1);

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

package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.moderation.application.port.out.BlockedIpRepository;
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

	@Autowired
	private IpBlockService ipBlocks;

	@Autowired
	private BlockedIpRepository blockedIps;

	private CenarioAutoBan cenario;

	@BeforeEach
	void prepararCenario() throws Exception {
		cenario = new CenarioAutoBan(worker, jdbc);
	}

	@AfterEach
	void limparCenario() {
		cenario.close();
	}

	@Test
	void theAttackPatternIsPurgedEvenWhenTheAccountLooksLikeStaff() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("probe_purge_target", cenario.cliente());

		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", "probe-purge-target", "note", "Nomeado no cenário de teste."))))
			.andExpect(status().isOk());

		// Caminho independente do score: o padrão do ataque é banido e limpo.
		org.assertj.core.api.Assertions.assertThat(worker.purgeAttackPatterns()).isGreaterThanOrEqualTo(1);

		mockMvc.perform(post("/auth/login")
				.with(cenario.cliente())
				.header("Idempotency-Key", "autoban-purge-login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "probe_purge_target@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));

		jdbc.update("delete from moderator_applications where user_id = ?",
			target.userId());
	}

	@Test
	void aStaffAccountCarryingTheAttackSignatureIsStillPunished() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("group-admin");
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("probe_staff_target", cenario.cliente());

		// A conta entra na equipe (a automação normalmente não pune equipe)...
		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"username", "probe-staff-target", "note", "Nomeado no cenário de teste."))))
			.andExpect(status().isOk());

		cenario.executarRodada();

		// ...mas carrega o nome do ataque: é conta comprometida, não equipe.
		mockMvc.perform(post("/auth/login")
				.with(cenario.cliente())
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
	void naoContaminaAutenticacaoDepoisDeFixturesDeOutrasClasses() throws Exception {
		var anterior = new CenarioAutoBan(worker, jdbc);
		ipBlocks.block("fixture:anterior", anterior.endereco(), "Bloqueio de outro cenário de teste.");
		try {
			var helper = new TestAuthHelper(mockMvc, objectMapper);
			for (int indice = 0; indice < 3; indice++) {
				helper.authenticate("probe_isolamento_lote_" + indice, cenario.cliente());
			}
			var report = cenario.executarRodada();
			assertThat(report.blockedIps()).contains(cenario.endereco());
			mockMvc.perform(post("/auth/login")
					.with(cenario.cliente())
					.header("Idempotency-Key", "isolamento-bloqueio-login")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of(
						"email", "probe_isolamento_lote_0@test.com", "password", "Test1234!"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("IP_BLOCKED"));

			cenario.close();
			assertThat(blockedIps.existsByIp(cenario.endereco())).isFalse();
			assertThat(blockedIps.existsByIp(anterior.endereco())).isTrue();
			helper.authenticate("isolamento_cenario_seguinte", cenario.cliente());
		}
		finally {
			jdbc.update("delete from blocked_ips where ip = ?", anterior.endereco());
		}
	}

	@Test
	void bansAndSanitizesAnAccountAboveTheRiskCeiling() throws Exception {
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("probe_auto_target", cenario.cliente());

		MvcResult published = mockMvc.perform(post("/feed/posts")
				.with(cenario.cliente())
				.header("X-Session-Token", target.token())
				.header("Idempotency-Key", "autoban-post-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "Conteúdo de conta em série.", "tags", List.of(),
					"mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		String postId = objectMapper.readTree(published.getResponse().getContentAsString()).get("id").asText();

		var report = cenario.executarRodada();
		assertThat(report.banned()).isGreaterThanOrEqualTo(1);

		// Sessão revogada e login bloqueado.
		mockMvc.perform(get("/auth/me").with(cenario.cliente()).header("X-Session-Token", target.token()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/login")
				.with(cenario.cliente())
				.header("Idempotency-Key", "autoban-login-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"email", "probe_auto_target@test.com", "password", "Test1234!"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));

		// Sanitização: o post saiu do banco, não só da listagem.
		mockMvc.perform(get("/feed/posts/" + postId).with(cenario.cliente())).andExpect(status().isNotFound());
	}
}

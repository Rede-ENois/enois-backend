package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gestão de moderadores por administrador e punição por votação:
 * - admin nomeia e remove moderadores; moderador não nomeia ninguém;
 * - abaixo de 10 moderadores a votação nem abre (só admin pune);
 * - a partir de 10, a punição só é aplicada com mais de 50% dos moderadores concordando.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModeratorAppointmentAndSanctionTests {

	private static final int MODERATORS = 10;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "ms_admin@test.com");
		registry.add("rede.moderation.moderator-min-reputation", () -> "-1");
	}

	@Test
	void onlyAdminAppointsAndRevokesModerators() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("ms_admin");
		var candidate = new TestAuthHelper(mockMvc, objectMapper).authenticate("ms_single_mod");
		var outsider = new TestAuthHelper(mockMvc, objectMapper).authenticate("ms_outsider");

		// Ninguém além de admin nomeia.
		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", outsider.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "ms-single-mod"))))
			.andExpect(status().isForbidden());

		// Nomeação direta: já entra como moderador, sem candidatura.
		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "ms-single-mod",
					"note", "Nomeado para o time de moderação."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("ms-single-mod"))
			.andExpect(jsonPath("$.status").value("APPROVED"));

		mockMvc.perform(get("/moderation/me").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moderator").value(true))
			.andExpect(jsonPath("$.moderatorCount").isNumber())
			.andExpect(jsonPath("$.minModeratorsForSanction").value(MODERATORS));

		// A lista de gestão mostra o nomeado.
		mockMvc.perform(get("/admin/moderators").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.username == 'ms-single-mod')].status").value("APPROVED"));

		// Nomear de novo é conflito.
		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "ms-single-mod"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_MODERATOR"));

		// Moderador não aprova a própria nomeação nem outros moderadores.
		mockMvc.perform(get("/admin/moderators").header("X-Session-Token", candidate.token()))
			.andExpect(status().isForbidden());

		// Remoção pelo admin.
		String candidateId = me(candidate).get("application").get("userId").asText();
		mockMvc.perform(post("/admin/moderators/" + candidateId + "/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "Ciclo encerrado."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVOKED"));

		mockMvc.perform(get("/moderation/me").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moderator").value(false));
	}

	@Test
	void sanctionVotingRequiresTenModeratorsAndAbsoluteMajority() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("ms_admin");

		// Zera os moderadores aprovados para o cenário ser determinístico: a suíte inteira
		// compartilha o mesmo H2. Cada teste cria e usa seus próprios moderadores.
		clearModerators(admin);
		assertThat(moderatorCount(admin)).isZero();

		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("ms_target");
		assertThat(canPublish(target, "ms-target-inicial")).isTrue();

		// Antes de 10: votação não abre; admin pune direto.
		mockMvc.perform(post("/moderation/sanction-proposals")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "ms-target", "type", "RESTRICTION",
					"reason", "Motivo de teste com tamanho suficiente"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NOT_ENOUGH_MODERATORS"));

		// Nomeia 10 moderadores.
		List<TestAuthHelper.AuthSession> moderators = new ArrayList<>();
		for (int i = 1; i <= MODERATORS; i++) {
			TestAuthHelper.AuthSession moderator = new TestAuthHelper(mockMvc, objectMapper)
				.authenticate("ms_mod" + i);
			moderators.add(moderator);
			appoint(admin, "ms-mod" + i);
		}
		assertThat(moderatorCount(admin)).isEqualTo(MODERATORS);

		mockMvc.perform(get("/moderation/me").header("X-Session-Token", moderators.get(0).token()))
			.andExpect(jsonPath("$.moderatorCount").value(MODERATORS));

		// Um moderador propõe; o voto dele já entra na contagem (1/6).
		MvcResult proposed = mockMvc.perform(post("/moderation/sanction-proposals")
				.header("X-Session-Token", moderators.get(0).token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "ms-target", "type", "RESTRICTION",
					"reason", "Assédio recorrente em comentários após avisos."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.approveVotes").value(1))
			.andExpect(jsonPath("$.requiredVotes").value(6))
			.andExpect(jsonPath("$.moderatorCount").value(10))
			.andExpect(jsonPath("$.myVote").value("APPROVE"))
			.andReturn();
		String proposalId = objectMapper.readTree(proposed.getResponse().getContentAsString())
			.get("id").asText();

		// Proposta duplicada contra o mesmo alvo não abre outra votação.
		mockMvc.perform(post("/moderation/sanction-proposals")
				.header("X-Session-Token", moderators.get(1).token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "ms-target", "type", "RESTRICTION",
					"reason", "Outra proposta enquanto a primeira está aberta."))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROPOSAL_ALREADY_PENDING"));

		// Usuário comum não vota.
		mockMvc.perform(post("/moderation/sanction-proposals/" + proposalId + "/vote")
				.header("X-Session-Token", target.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "APPROVE"))))
			.andExpect(status().isForbidden());

		// Votos 2..5: ainda falta quórum (precisa de 6).
		for (int i = 1; i <= 4; i++) {
			mockMvc.perform(post("/moderation/sanction-proposals/" + proposalId + "/vote")
					.header("X-Session-Token", moderators.get(i).token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("vote", "APPROVE", "note", "Concordo."))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.decision").value("PENDING"));
		}
		assertThat(canPublish(target, "ms-target-ainda-publica")).isTrue();

		// Sexto voto fecha o quórum: restrição aplicada.
		mockMvc.perform(post("/moderation/sanction-proposals/" + proposalId + "/vote")
				.header("X-Session-Token", moderators.get(5).token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "APPROVE", "note", "Concordo."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.approveVotes").value(6))
			.andExpect(jsonPath("$.decision").value("APPLIED"))
			.andExpect(jsonPath("$.status").value("APPLIED"));

		assertThat(canPublish(target, "ms-target-restrito")).isFalse();

		// Votação encerrada não aceita novos votos.
		mockMvc.perform(post("/moderation/sanction-proposals/" + proposalId + "/vote")
				.header("X-Session-Token", moderators.get(6).token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("vote", "APPROVE"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_DECIDED"));
	}

	@Test
	void rejectedMajorityDoesNotPunish() throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("ms_admin");
		clearModerators(admin);
		var target = new TestAuthHelper(mockMvc, objectMapper).authenticate("ms_target_reject");

		List<TestAuthHelper.AuthSession> moderators = new ArrayList<>();
		for (int i = 1; i <= MODERATORS; i++) {
			TestAuthHelper.AuthSession moderator = new TestAuthHelper(mockMvc, objectMapper)
				.authenticate("ms_rej_mod" + i);
			moderators.add(moderator);
			appoint(admin, "ms-rej-mod" + i);
		}
		long total = moderatorCount(admin);
		int required = (int) (total / 2 + 1);

		MvcResult proposed = mockMvc.perform(post("/moderation/sanction-proposals")
				.header("X-Session-Token", moderators.get(0).token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", "ms-target-reject", "type", "BAN",
					"reason", "Denúncia grave que a maioria pode rejeitar."))))
			.andExpect(status().isOk())
			.andReturn();
		String proposalId = objectMapper.readTree(proposed.getResponse().getContentAsString())
			.get("id").asText();

		// O autor da proposta vota APPROVE; os demais rejeitam até passar da metade.
		for (int i = 1; i <= required; i++) {
			mockMvc.perform(post("/moderation/sanction-proposals/" + proposalId + "/vote")
					.header("X-Session-Token", moderators.get(i).token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("vote", "REJECT",
						"note", "Sem evidências suficientes."))))
				.andExpect(status().isOk());
		}

		mockMvc.perform(get("/moderation/sanction-proposals")
				.header("X-Session-Token", moderators.get(1).token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + proposalId + "')]").isEmpty());

		assertThat(canPublish(target, "ms-target-reject-livre")).isTrue();
	}

	private void clearModerators(TestAuthHelper.AuthSession admin) throws Exception {
		MvcResult listed = mockMvc.perform(get("/admin/moderators").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andReturn();
		JsonNode moderators = objectMapper.readTree(listed.getResponse().getContentAsString());
		for (JsonNode moderator : moderators) {
			mockMvc.perform(post("/admin/moderators/" + moderator.get("userId").asText() + "/revoke")
					.header("X-Session-Token", admin.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("note", "Reset do cenário de teste."))))
				.andExpect(status().isOk());
		}
	}

	private long moderatorCount(TestAuthHelper.AuthSession admin) throws Exception {
		MvcResult listed = mockMvc.perform(get("/admin/moderators").header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(listed.getResponse().getContentAsString()).size();
	}

	private void appoint(TestAuthHelper.AuthSession admin, String username) throws Exception {
		mockMvc.perform(post("/admin/moderators")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", username,
					"note", "Nomeado para o teste de votação."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));
	}

	private JsonNode me(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(get("/moderation/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private boolean canPublish(TestAuthHelper.AuthSession session, String idempotencyKey) throws Exception {
		int status = mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("topic", "TECNOLOGIA",
					"type", "INSIGHT", "content", "teste de sanção " + idempotencyKey,
					"tags", List.of(), "mediaIds", List.of()))))
			.andReturn().getResponse().getStatus();
		return status == 201;
	}
}

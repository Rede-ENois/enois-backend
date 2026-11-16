package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Triagem com score explicável e ações em lote: o score sai do retrato congelado da candidatura
 * e cada ponto vem com o motivo escrito; o lote devolve o desfecho de cada candidatura, movida
 * ou recusada com o código próprio — nunca some pela metade em silêncio.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CandidateTriageTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	/** Mesmos overrides dos outros fluxos de vaga, para compartilhar um contexto de teste só. */
	@MockitoBean
	private ReputationService reputationService;

	@MockitoBean
	private EmailService emailService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		when(reputationService.reputationOf(anyString())).thenReturn(300);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void triageRanksByExplainableScoreAndBatchMovesItemByItem() throws Exception {
		var owner = auth.authenticate("tri_owner");
		var matching = auth.authenticate("tri_candidate_match");
		var other = auth.authenticate("tri_candidate_other");
		String uid = UUID.randomUUID().toString().substring(0, 8);

		// Perfil do candidato aderente: Java no retrato (o de outros não). A candidatura é sempre
		// da identidade profissional ativa, então ela precisa existir antes de aplicar.
		auth.activateProfessional(matching);
		auth.activateProfessional(other);
		setSkills(matching, List.of("Java", "Spring"));
		String jobId = createJob(owner, "Vaga triada " + uid, List.of("Java"));
		// A entrada do fluxo é a primeira etapa; o movimento em lote vai para a segunda.
		json(mockMvc.perform(post("/jobs/" + jobId + "/process/stages")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Triagem"))))
			.andExpect(status().isOk())
			.andReturn());
		String secondStage = json(mockMvc.perform(post("/jobs/" + jobId + "/process/stages")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Entrevista"))))
			.andExpect(status().isOk())
			.andReturn()).get("id").asText();

		String matchApplication = apply(matching, jobId);
		String otherApplication = apply(other, jobId);

		// Fila de triagem: quem tem o requisito vem primeiro, e o score explica o porquê.
		MvcResult ranking = mockMvc.perform(get("/jobs/" + jobId + "/process/candidates")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].applicationId").value(matchApplication))
			.andExpect(jsonPath("$[0].score.available").value(true))
			.andExpect(jsonPath("$[0].score.components[0].code").value("SKILLS"))
			.andReturn();
		String reason = json(ranking).get(0).get("score").get("components").get(0).get("label").asText();
		org.junit.jupiter.api.Assertions.assertTrue(reason.contains("1 de 1 requisitos da vaga: Java"),
			"o motivo do score precisa citar o requisito atendido, veio: " + reason);
		// O score do candidato aderente tem os dois sinais escritos (habilidades + identidade).
		org.junit.jupiter.api.Assertions.assertEquals(2,
			json(ranking).get(0).get("score").get("components").size());
		// Sem habilidade no retrato não há comparação: indisponível, zero, e nunca "a pior nota".
		mockMvc.perform(get("/jobs/" + jobId + "/process/candidates")
				.header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$[1].applicationId").value(otherApplication))
			.andExpect(jsonPath("$[1].score.available").value(false))
			.andExpect(jsonPath("$[1].score.total").value(0));

		// Quem não vê candidaturas não vê a fila.
		mockMvc.perform(get("/jobs/" + jobId + "/process/candidates")
				.header("X-Session-Token", other.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		// Lote: move quem tem o requisito e reprova o outro com o mesmo retorno; o desfecho vem
		// item a item.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/batch-move")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"applicationIds", List.of(matchApplication),
					"stageId", secondStage,
					"kind", "ADVANCE"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].applicationId").value(matchApplication))
			.andExpect(jsonPath("$[0].movement.toStageId").value(secondStage));

		String rejection = "Sem aderência com a vaga neste momento.";
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/batch-move")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"applicationIds", List.of(otherApplication),
					"kind", "REJECT",
					"note", rejection))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].movement.kind").value("REJECT"))
			.andExpect(jsonPath("$[0].movement.note").value(rejection))
			.andExpect(jsonPath("$[0].errorCode").doesNotExist())
			.andReturn();

		// Reprovação em lote sem motivo é recusa explícita, não sucesso silencioso.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/batch-move")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"applicationIds", List.of(otherApplication),
					"kind", "REJECT",
					"note", "  "))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("FEEDBACK_REQUIRED"));
		// O retorno escrito no lote chegou à candidatura — é o que o candidato lê.
		mockMvc.perform(get("/jobs/" + jobId + "/applications").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("NOT_SELECTED"))
			.andExpect(jsonPath("$[0].feedback").value(rejection));

		// Lote com candidatura já reprovada: 409 nomeando a candidatura, e nada do lote é movido.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/batch-move")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"applicationIds", List.of(otherApplication),
					"stageId", secondStage,
					"kind", "ADVANCE"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPLICATION_REJECTED"));
		// Lote vazio é recusa própria.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/batch-move")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"applicationIds", List.of(),
					"stageId", secondStage,
					"kind", "ADVANCE"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NO_APPLICATIONS_SELECTED"));
		// Lote da candidatura já movida para a mesma etapa: recusa por item, não 500.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/batch-move")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"applicationIds", List.of(matchApplication, matchApplication),
					"stageId", secondStage,
					"kind", "ADVANCE"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].errorCode").value("ALREADY_IN_STAGE"))
			.andExpect(jsonPath("$[0].movement").doesNotExist());

		// Terceiro sem TRIAGE não move em lote.
		mockMvc.perform(post("/jobs/" + jobId + "/process/applications/batch-move")
				.header("X-Session-Token", other.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"applicationIds", List.of(matchApplication),
					"stageId", secondStage,
					"kind", "ADVANCE"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));
	}

	@Test
	void scoreIsHonestWhenTheJobSaysNothingToCompare() throws Exception {
		var owner = auth.authenticate("tri_plain_owner");
		var candidate = auth.authenticate("tri_plain_candidate");
		String uid = UUID.randomUUID().toString().substring(0, 8);

		auth.activateProfessional(candidate);
		setSkills(candidate, List.of("Java"));
		// Vaga sem requisitos: sem comparação de habilidades, o score diz que não há o que comparar
		// em vez de inventar um zero.
		String jobId = json(mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.ofEntries(
					Map.entry("title", "Vaga sem requisitos " + uid),
					Map.entry("company", "Acme"),
					Map.entry("location", "Remoto"),
					Map.entry("type", "CLT"),
					Map.entry("level", "PLENO"),
					Map.entry("currency", "BRL"),
					Map.entry("salaryMin", "1500"),
					Map.entry("salaryPeriod", "MONTH"),
					Map.entry("description", "Vaga de teste sem lista de habilidades."),
					Map.entry("skills", List.of()),
					Map.entry("communityAgreement", true),
					Map.entry("applicationFields", List.of())))))
			.andExpect(status().isCreated())
			.andReturn()).get("id").asText();
		apply(candidate, jobId);

		mockMvc.perform(get("/jobs/" + jobId + "/process/candidates")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].score.available").value(false))
			.andExpect(jsonPath("$[0].score.total").value(0))
			// Identidade entra como sinal do retrato, com o motivo escrito.
			.andExpect(jsonPath("$[0].score.components[0].code").value("IDENTITY"));
	}

	private String createJob(TestAuthHelper.AuthSession owner, String title, List<String> skills) throws Exception {
		return json(mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.ofEntries(
					Map.entry("title", title),
					Map.entry("company", "Acme"),
					Map.entry("location", "Remoto"),
					Map.entry("type", "CLT"),
					Map.entry("level", "PLENO"),
					Map.entry("currency", "BRL"),
					Map.entry("salaryMin", "1500"),
					Map.entry("salaryPeriod", "MONTH"),
					Map.entry("description", "Vaga de teste com valor."),
					Map.entry("skills", skills),
					Map.entry("communityAgreement", true),
					Map.entry("applicationFields", List.of())))))
			.andExpect(status().isCreated())
			.andReturn()).get("id").asText();
	}

	/** Habilidades do perfil da conta, que entram no retrato congelado da candidatura. */
	private void setSkills(TestAuthHelper.AuthSession who, List<String> skills) throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/me/profile")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("skills", skills))))
			.andExpect(status().isOk());
	}

	private String apply(TestAuthHelper.AuthSession who, String jobId) throws Exception {
		return json(mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", who.token())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"Tenho interesse.\"}"))
			.andExpect(status().isCreated())
			.andReturn()).get("id").asText();
	}

	private JsonNode json(MvcResult result) throws java.io.UnsupportedEncodingException {
		return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
	}
}

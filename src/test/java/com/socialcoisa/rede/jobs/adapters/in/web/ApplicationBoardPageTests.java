package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Tabela paginada das candidaturas: cursor keyset, filtro por estado e busca, do servidor.
 *
 * <p>O que provava o problema: o board antigo trazia a vaga inteira e cortava no teto, então
 * era impossível ver a candidatura de número 101. Os testes abaixo paginam até o fim e conferem
 * que cada linha aparece exatamente uma vez — sem repetir, sem pular.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationBoardPageTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ReputationService reputationService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		when(reputationService.reputationOf(anyString())).thenReturn(500);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void walksEveryApplicationOnceByCursorBeyondTheOldCap() throws Exception {
		var employer = auth.authenticate("page_owner");
		String jobId = createJob(employer);
		List<String> created = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			created.add(apply(jobId, candidate("page_cand_" + index)));
		}

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			MvcResult result = mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
					.header("X-Session-Token", employer.token())
					.param("limit", "5")
					.param("cursor", cursor == null ? "" : cursor))
				.andExpect(status().isOk())
				.andReturn();
			var body = objectMapper.readTree(result.getResponse().getContentAsString());
			body.get("items").forEach(item -> seen.add(item.get("applicationId").asText()));
			cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asText();
			pages++;
		}
		while (cursor != null && pages < 10);

		assertThat(seen).hasSize(12).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
		assertThat(pages).isEqualTo(3);
	}

	@Test
	void lastPageCarriesNoCursorSoTheClientStops() throws Exception {
		var employer = auth.authenticate("page_tail_owner");
		String jobId = createJob(employer);
		apply(jobId, candidate("page_tail_cand"));

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", employer.token()).param("limit", "5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void statusFilterAndSearchNarrowTheListInTheDatabase() throws Exception {
		var employer = auth.authenticate("page_filter_owner");
		String jobId = createJob(employer);
		var alice = candidate("alice");
		var bruno = candidate("bruno");
		// O nome que o recrutador lê no card: o do perfil profissional, congelado na inscrição.
		renameProfessional(alice, "Alice Silva");
		renameProfessional(bruno, "Bruno Costa");
		String aliceApplication = apply(jobId, alice);
		apply(jobId, bruno);

		// Alice avança de etapa: a tabela filtra só quem já foi movido.
		mockMvc.perform(patch("/jobs/" + jobId + "/applications/" + aliceApplication)
				.header("X-Session-Token", employer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"IN_REVIEW\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", employer.token()).param("status", "IN_REVIEW"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].applicationId").value(aliceApplication))
			.andExpect(jsonPath("$.items[0].status").value("IN_REVIEW"));

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", employer.token()).param("status", "APPLIED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));

		// A busca casa o nome do candidato no retrato gravado na inscrição.
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", employer.token()).param("search", "alice"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].applicationId").value(aliceApplication));

		// Termo que não casa ninguém devolve lista vazia, não a lista inteira.
		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", employer.token()).param("search", "fulano"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void garbageInputIsARefusalNotAnEmptyList() throws Exception {
		var employer = auth.authenticate("page_bad_owner");
		String jobId = createJob(employer);

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", employer.token()).param("status", "CONTRATADO"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_APPLICATION_STATUS"));

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications")
				.header("X-Session-Token", employer.token()).param("cursor", "nao-e-cursor"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}

	@Test
	void onlyTheJobOwnerReadsTheList() throws Exception {
		var employer = auth.authenticate("page_private_owner");
		var stranger = auth.authenticate("page_stranger");
		String jobId = createJob(employer);
		apply(jobId, candidate("page_private_cand"));

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications").header("X-Session-Token", stranger.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_OWNER"));

		mockMvc.perform(get("/jobs/" + jobId + "/process/applications"))
			.andExpect(status().isUnauthorized());
	}

	// ---------- Apoio ----------

	private TestAuthHelper.AuthSession candidate(String id) throws Exception {
		var session = auth.authenticate(id);
		auth.activateProfessional(session);
		return session;
	}

	/** O nome que aparece no card do recrutador: o do perfil profissional, no momento da inscrição. */
	private void renameProfessional(TestAuthHelper.AuthSession session, String name) throws Exception {
		mockMvc.perform(post("/me/business-profile/name")
				.header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isOk());
	}

	private String createJob(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "page-job-" + session.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"Pessoa Desenvolvedora","company":"Acme","location":"Remoto","type":"CLT",
						 "level":"PLENO","currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH",
						 "description":"Vaga de teste da tabela paginada.","skills":["Java"],
						 "communityAgreement":true,"applicationFields":[]}"""))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private String apply(String jobId, TestAuthHelper.AuthSession candidate) throws Exception {
		MvcResult result = mockMvc.perform(post("/jobs/" + jobId + "/applications")
				.header("X-Session-Token", candidate.token())
				.header("Idempotency-Key", "page-apply-" + candidate.userId() + "-" + jobId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
					Map.of("message", "Tenho interesse", "answers", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

}

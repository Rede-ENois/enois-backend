package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Banco de talentos: a pessoa libera a candidatura por organização, o recrutamento vê só quem
 * liberou, e retirar (ou apagar a conta) tira a linha na hora.
 *
 * <p>O teste insiste no que é a razão de existir da tabela — o consentimento. Uma busca que
 * devolvesse quem nunca liberou seria o bug mais caro aqui, então o caso principal é esse: um
 * candidato que existe, tem perfil e usuário, e mesmo assim não aparece.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TalentPoolTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private ReputationService reputationService;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		when(reputationService.reputationOf(anyString())).thenReturn(300);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void semLiberarNaoApareceMesmoComBusca() throws Exception {
		var employer = auth.authenticate("pool_owner_a");
		var candidate = auth.authenticate("pool_cand_a");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Talentos A");

		// A lista começa vazia e continua vazia com busca pelo nome exato do candidato: quem não
		// liberou não é encontrado por critério nenhum.
		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).param("search", candidate.userId())
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void liberarDaVisibilidadeARetirarTiraNaHoraEOMeuMostraOndeEstou() throws Exception {
		var employer = auth.authenticate("pool_owner_b");
		var candidate = auth.authenticate("pool_cand_b");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Talentos B");

		mockMvc
			.perform(post("/jobs/recruitment/talent-pool/" + companyId + "/opt-in")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.companyId").value(companyId))
			.andExpect(jsonPath("$.companyName").value("Talentos B"))
			.andExpect(jsonPath("$.consentVersion").isNotEmpty())
			.andExpect(jsonPath("$.consentedAt").isNotEmpty());

		// O próprio candidato vê onde está listado — e é isso que lhe dá como sair.
		mockMvc.perform(get("/jobs/recruitment/talent-pool/mine").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].companyId").value(companyId))
			.andExpect(jsonPath("$[0].companyName").value("Talentos B"));

		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].userId").value(candidate.userId()));

		mockMvc
			.perform(delete("/jobs/recruitment/talent-pool/" + companyId + "/opt-in")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk());
		// Retirar de novo não muda nada: o estado desejado já vale.
		mockMvc
			.perform(delete("/jobs/recruitment/talent-pool/" + companyId + "/opt-in")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk());

		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
		mockMvc.perform(get("/jobs/recruitment/talent-pool/mine").header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void liberarDeNovoDepoisDeRetirarVoltaAMostrar() throws Exception {
		var employer = auth.authenticate("pool_owner_c");
		var candidate = auth.authenticate("pool_cand_c");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Talentos C");

		optIn(candidate, companyId);
		mockMvc
			.perform(delete("/jobs/recruitment/talent-pool/" + companyId + "/opt-in")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk());
		optIn(candidate, companyId);

		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].userId").value(candidate.userId()));
	}

	@Test
	void soQuemRecrutaNaOrganizacaoLeEOrganizacaoDesconhecidaEh404() throws Exception {
		var employer = auth.authenticate("pool_owner_d");
		var stranger = auth.authenticate("pool_stranger_d");
		var candidate = auth.authenticate("pool_cand_d");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Talentos D");
		optIn(candidate, companyId);

		// Estranho não recebe lista vazia silenciosa: recebe 403.
		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).header("X-Session-Token", stranger.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("RECRUITMENT_FORBIDDEN"));

		mockMvc
			.perform(post("/jobs/recruitment/talent-pool/company_nao_existe/opt-in")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"));
	}

	@Test
	void recadoAcumulaSemConsentimentoNovoELimitaTamanho() throws Exception {
		var employer = auth.authenticate("pool_owner_e");
		var candidate = auth.authenticate("pool_cand_e");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Talentos E");

		// Sem liberar, não há recado: 404 explícito, não criação silenciosa.
		mockMvc
			.perform(patch("/jobs/recruitment/talent-pool/" + companyId + "/note")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\":\"Aberto a remoto.\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_IN_TALENT_POOL"));

		optIn(candidate, companyId);
		mockMvc
			.perform(patch("/jobs/recruitment/talent-pool/" + companyId + "/note")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"note\":\"Aberto a remoto.\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.note").value("Aberto a remoto."));

		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].note").value("Aberto a remoto."));
	}

	@Test
	void paginacaoPorCursorCobreTodosOsLiberadosSemRepetir() throws Exception {
		var employer = auth.authenticate("pool_owner_f");
		String companyId = createPage(employer, "Talentos F");

		java.util.Set<String> expected = new java.util.LinkedHashSet<>();
		for (int i = 0; i < 5; i++) {
			var candidate = auth.authenticate("pool_cand_f" + i);
			auth.activateProfessional(candidate);
			optIn(candidate, companyId);
			expected.add(candidate.userId());
		}

		java.util.Set<String> collected = new java.util.LinkedHashSet<>();
		String cursor = null;
		int pages = 0;
		do {
			MockHttpServletRequestBuilder request = get("/jobs/recruitment/talent-pool/" + companyId)
				.param("limit", "2")
				.header("X-Session-Token", employer.token());
			if (cursor != null) {
				request = request.param("cursor", cursor);
			}
			MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
			var body = objectMapper.readTree(result.getResponse().getContentAsString());
			for (var item : body.get("items")) {
				collected.add(item.get("userId").asText());
			}
			cursor = body.has("nextCursor") && !body.get("nextCursor").isNull() ? body.get("nextCursor").asText()
				: null;
			pages++;
		}
		while (cursor != null && pages < 10);

		org.junit.jupiter.api.Assertions.assertEquals(expected, collected);
		org.junit.jupiter.api.Assertions.assertNull(cursor);
	}

	@Test
	void cursorInvalidoEh400() throws Exception {
		var employer = auth.authenticate("pool_owner_g");
		String companyId = createPage(employer, "Talentos G");
		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).param("cursor", "nao-e-cursor")
				.header("X-Session-Token", employer.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}

	@Test
	void apagarAContaLevaOConsentimentoJunto() throws Exception {
		var employer = auth.authenticate("pool_owner_h");
		var candidate = auth.authenticate("pool_cand_h");
		auth.activateProfessional(candidate);
		String companyId = createPage(employer, "Talentos H");
		optIn(candidate, companyId);
		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));

		deleteAccount(candidate);

		// A linha some junto com a conta: sem conta não há consentimento vivo para listar.
		mockMvc
			.perform(get("/jobs/recruitment/talent-pool/" + companyId).header("X-Session-Token", employer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	private void deleteAccount(TestAuthHelper.AuthSession candidate) throws Exception {
		mockMvc
			.perform(post("/auth/account/delete")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("password", "Test1234!"))))
			.andExpect(status().isOk());
	}

	private void optIn(TestAuthHelper.AuthSession candidate, String companyId) throws Exception {
		mockMvc
			.perform(post("/jobs/recruitment/talent-pool/" + companyId + "/opt-in")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk());
	}

	private String createPage(TestAuthHelper.AuthSession who, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/empresas")
				.header("X-Session-Token", who.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

}

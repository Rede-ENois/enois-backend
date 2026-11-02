package com.socialcoisa.rede.moderation.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Denunciar uma pagina empresarial e a revisao que a REMOVE do diretorio.
 *
 * Tranca: a denuncia entra com alvo COMPANY; o painel mostra o conteudo da pagina e o dono como
 * responsavel; a acao própria fecha a pagina (404 para quem visita, some do diretorio) sem tocar
 * na conta do dono; banir/penalizar o dono por causa da pagina e recusado.
 */
@SpringBootTest(properties = "rede.support.email=support@test.com")
@AutoConfigureMockMvc
class CompanyReportTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@MockitoBean
	private EmailService emailService;

	@org.springframework.test.context.DynamicPropertySource
	static void adminAllowlist(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "crep_admin@test.com");
	}

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private JsonNode createCompany(TestAuthHelper.AuthSession owner, String name) throws Exception {
		MvcResult created = mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "crep-create-" + name)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name, "description", "Pagina criada para o teste de denuncia.",
					"sector", "Tecnologia", "size", "1-10", "location", "Remoto"))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString());
	}

	private String report(TestAuthHelper.AuthSession reporter, String companyId) throws Exception {
		MvcResult reported = mockMvc.perform(post("/reports")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", "crep-report-" + companyId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("targetType", "COMPANY",
					"targetId", companyId, "reason", "SPAM",
					"description", "A pagina usa o diretorio para divulgar golpe."))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(reported.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void companyReportEmailOffersTheCompanyActionInsteadOfPostActions() throws Exception {
		var owner = auth.authenticate("crep_mail_owner");
		var reporter = auth.authenticate("crep_mail_reporter");
		JsonNode company = createCompany(owner, "Empresa Crepe Mail");
		report(reporter, company.get("id").asText());

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(emailService).sendHtmlEmail(eq("support@test.com"), anyString(), body.capture());
		assertThat(body.getValue())
			.contains("?acao=empresa")
			.doesNotContain("?acao=banir", "?acao=penalizar", "?acao=excluir");
	}

	@Test
	void adminRemovesTheReportedCompanyWithoutTouchingTheOwnerAccount() throws Exception {
		var owner = auth.authenticate("crep_owner");
		var reporter = auth.authenticate("crep_reporter");
		var admin = auth.authenticate("crep_admin");
		JsonNode company = createCompany(owner, "Empresa Denunciada crep");
		String companyId = company.get("id").asText();
		String slug = company.get("slug").asText();

		String reportId = report(reporter, companyId);

		// O painel mostra o que a pagina diz sobre si e o dono como responsavel.
		mockMvc.perform(get("/admin/reports/" + reportId).header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authorId").value(owner.userId()))
			.andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("Empresa Denunciada crep")));

		// Banir o dono por causa da pagina e recusado: pagina se remove, conta nao.
		MvcResult banResult = mockMvc.perform(post("/admin/reports/" + reportId + "/ban")
				.header("X-Session-Token", admin.token()))
			.andReturn();
		assertThat(banResult.getResponse().getStatus())
			.as("ban: " + banResult.getResponse().getContentAsString()).isEqualTo(400);
		assertThat(banResult.getResponse().getContentAsString()).contains("COMPANY_TARGET");

		mockMvc.perform(post("/admin/reports/" + reportId + "/remove-company")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "crep-remove"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));

		// Pagina some do diretorio e da leitura publica; a conta do dono continua de pe.
		mockMvc.perform(get("/empresa/" + slug))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"));
		mockMvc.perform(get("/empresas").param("q", "Empresa Denunciada crep"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + companyId + "')]").isEmpty());
		mockMvc.perform(get("/me").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
	}

	@Test
	void onlyAnAdminRemovesAndTheActionRejectsOtherTargets() throws Exception {
		var owner = auth.authenticate("crep_owner2");
		var reporter = auth.authenticate("crep_reporter2");
		JsonNode company = createCompany(owner, "Outra Empresa crep");
		String companyId = company.get("id").asText();
		String reportId = report(reporter, companyId);

		mockMvc.perform(post("/admin/reports/" + reportId + "/remove-company")
				.header("X-Session-Token", reporter.token())
				.header("Idempotency-Key", "crep-remove-denied"))
			.andExpect(status().isForbidden());

		// A denuncia continua pendente para quem tenta de novo sem permissao.
		mockMvc.perform(get("/empresa/" + company.get("slug").asText()))
			.andExpect(status().isOk());
	}
}

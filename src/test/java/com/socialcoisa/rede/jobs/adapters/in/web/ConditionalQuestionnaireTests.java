package com.socialcoisa.rede.jobs.adapters.in.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.RequestBuilder;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Questionário com lógica condicional e autosave do rascunho: campo que só aparece quando a
 * resposta de outro campo casa (caixa e acento ignorados), invisível não pede resposta no envio,
 * configuração contraditória é recusa explícita, e o rascunho é um buffer pessoal que morre
 * quando a candidatura sai.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConditionalQuestionnaireTests {

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
	void conditionalFieldRulesAndDraftAutosave() throws Exception {
		var owner = auth.authenticate("cq_owner");
		var candidate = auth.authenticate("cq_candidate");
		auth.activateProfessional(candidate);
		String uid = UUID.randomUUID().toString().substring(0, 8);
		String body = jobBody("Vaga condicional " + uid, "f_disponibilidade", null);

		// Condicional apontando para origem que não existe: recusa na criação.
		mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("CONDITION_SOURCE_NOT_FOUND"));

		// Vaga real: a origem precede a condicional na lista e é referida PELO RÓTULO — na criação
		// os ids ainda não existem; o que fica gravado é o id resolvido, e o front o recebe.
		String jobId = json(mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(jobBody("Vaga condicional " + uid, "Tem disponibilidade para viagem?",
					"""
							{"label":"Tem disponibilidade para viagem?","type":"TEXT","required":true},
							""")))
			.andExpect(status().isCreated())
			.andReturn()).get("id").asText();
		String sourceFieldId = fieldIdOf(jobId, owner, "Tem disponibilidade para viagem?");

		// A vaga expõe id, rótulo, obrigatório e a condição — é o front que esconde.
		String conditionalFieldId = "";
		JsonNode fields = fieldsOf(jobId, owner);
		for (JsonNode field : fields) {
			if ("Carteira de motorista".equals(field.get("label").asText())) {
				conditionalFieldId = field.get("id").asText();
				org.junit.jupiter.api.Assertions.assertEquals(sourceFieldId,
					field.get("visibleIfFieldId").asText());
				org.junit.jupiter.api.Assertions.assertEquals("Sim", field.get("visibleIfValue").asText());
				org.junit.jupiter.api.Assertions.assertEquals(true, field.get("required").asBoolean());
			}
		}
		org.junit.jupiter.api.Assertions.assertFalse(conditionalFieldId.isEmpty());

		// Sem responder a origem (obrigatória): recusa nomeando o que falta.
		mockMvc.perform(applyBody(candidate, jobId, "{\"message\":\"Tenho interesse.\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_REQUIRED_ANSWERS"));

		// Respondeu "Sim": a condicional fica visível e obrigatória — sem resposta dela, barra.
		mockMvc.perform(applyBody(candidate, jobId,
				answersBody(sourceFieldId, "Sim")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_REQUIRED_ANSWERS"))
			.andExpect(jsonPath("$.message").value(
				org.hamcrest.Matchers.containsString("Carteira de motorista")));

		// Casagem da condição ignora caixa e acento: "SIM" revela igual a "Sim".
		mockMvc.perform(applyBody(candidate, jobId, answersBody(sourceFieldId, "SIM")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value(
				org.hamcrest.Matchers.containsString("Carteira de motorista")));

		// Respondeu "Não": a condicional fica invisível — o envio passa SEM a resposta dela.
		mockMvc.perform(applyBody(candidate, jobId, answersBody(sourceFieldId, "Não")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.answersCount").value(1));

		// Rascunho em outra vaga (sem candidatura ainda): grava, lê de volta, grava por cima.
		String draftJob = json(mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"Vaga rascunho %s","company":"Acme","location":"Remoto","type":"CLT",
						 "level":"PLENO","currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH",
						 "description":"Vaga de teste com valor.","skills":["Java"],"communityAgreement":true,
						 "applicationFields":[
						   {"label":"Conte algo","type":"TEXTAREA","required":false}
						 ]}""".formatted(uid)))
			.andExpect(status().isCreated())
			.andReturn()).get("id").asText();
		mockMvc.perform(put("/jobs/" + draftJob + "/applications/draft")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("answers", Map.of("rascunho", "metade")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.updatedAt").exists());
		mockMvc.perform(get("/jobs/" + draftJob + "/applications/draft")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answers['rascunho']").value("metade"));
		mockMvc.perform(put("/jobs/" + draftJob + "/applications/draft")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("answers", Map.of("rascunho", "completo")))))
			.andExpect(status().isOk());
		mockMvc.perform(get("/jobs/" + draftJob + "/applications/draft")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answers['rascunho']").value("completo"));

		// Rascunho é pessoal: terceiro lê o próprio vazio, não o do candidato.
		var stranger = auth.authenticate("cq_stranger");
		mockMvc.perform(get("/jobs/" + draftJob + "/applications/draft")
				.header("X-Session-Token", stranger.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answers").doesNotExist());

		// Enviou, o rascunho morre.
		mockMvc.perform(applyBody(candidate, draftJob, "{\"message\":\"Enviando.\"}"))
			.andExpect(status().isCreated());
		mockMvc.perform(get("/jobs/" + draftJob + "/applications/draft")
				.header("X-Session-Token", candidate.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answers").doesNotExist());

		// Vaga inexistente: rascunho responde 404 igual ao apply.
		mockMvc.perform(put("/jobs/nao-existe-" + uid + "/applications/draft")
				.header("X-Session-Token", candidate.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("answers", Map.of("a", "b")))))
			.andExpect(status().isNotFound());
	}

	@Test
	void conditionalConfigurationRefusalsAreExplicit() throws Exception {
		var owner = auth.authenticate("cq_owner2");
		String uid = UUID.randomUUID().toString().substring(0, 8);
		String template = """
				{"title":"Recusas %s","company":"Acme","location":"Remoto","type":"CLT",
				 "level":"PLENO","currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH",
				 "description":"Vaga de teste com valor.","skills":["Java"],"communityAgreement":true,
				 "applicationFields":[__FIELDS__]}""".formatted(uid);

		// Condicional sem dizer de qual campo depende.
		mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(template.replace("__FIELDS__", """
						{"label":"Pergunta","type":"TEXT","required":false},
						{"label":"Dependente","type":"TEXT","required":false,
						 "visibleIfFieldId":"","visibleIfValue":"Sim"}""")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("CONDITION_REQUIRES_SOURCE"));

		// Condicional sem o valor que revela o campo.
		mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(template.replace("__FIELDS__", """
						{"label":"Pergunta","type":"TEXT","required":false},
						{"label":"Dependente","type":"TEXT","required":false,
						 "visibleIfFieldId":"field_qualquer","visibleIfValue":"  "}""")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("CONDITION_REQUIRES_VALUE"));

		// Origem que não existe nesta vaga.
		mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(template.replace("__FIELDS__", """
						{"label":"Pergunta","type":"TEXT","required":false},
						{"label":"Dependente","type":"TEXT","required":false,
						 "visibleIfFieldId":"field_qualquer","visibleIfValue":"Sim"}""")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("CONDITION_SOURCE_NOT_FOUND"));

		// Ciclo por construção é impossível: a origem precisa preceder na lista, então um campo
		// só consegue apontar para quem já existe — provado por esta vaga de dois campos criar bem.
		mockMvc.perform(post("/jobs")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(template.replace("__FIELDS__", """
						{"label":"Pergunta","type":"TEXT","required":false},
						{"label":"Dependente","type":"TEXT","required":false,
						 "visibleIfValue":"Sim"}""")))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("CONDITION_REQUIRES_SOURCE"));
	}

	private String jobBody(String title, String sourceId, String extraPrefix) {
		return """
				{"title":"%s","company":"Acme","location":"Remoto","type":"CLT","level":"PLENO",
				 "currency":"BRL","salaryMin":"1500","salaryPeriod":"MONTH","description":"Vaga de teste com valor.",
				 "skills":["Java"],"communityAgreement":true,"applicationFields":[%s
				   {"label":"Carteira de motorista","type":"TEXT","required":true,
				    "visibleIfFieldId":"%s","visibleIfValue":"Sim"}]}"""
			.formatted(title, extraPrefix == null ? "" : extraPrefix, sourceId);
	}

	private String answersBody(String fieldId, String value) {
		return """
				{"message":"Quero muito.","answers":[{"fieldId":"%s","value":"%s"}]}"""
			.formatted(fieldId, value.replace("\"", "\\\""));
	}

	private String fieldIdOf(String jobId, TestAuthHelper.AuthSession owner, String label) throws Exception {
		for (JsonNode field : fieldsOf(jobId, owner)) {
			if (label.equals(field.get("label").asText())) {
				return field.get("id").asText();
			}
		}
		throw new IllegalStateException("campo não encontrado: " + label);
	}

	private JsonNode fieldsOf(String jobId, TestAuthHelper.AuthSession owner) throws Exception {
		JsonNode job = json(mockMvc.perform(get("/jobs/" + jobId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andReturn());
		return job.get("applicationFields");
	}

	private RequestBuilder applyBody(TestAuthHelper.AuthSession who, String jobId, String body) {
		return post("/jobs/" + jobId + "/applications")
			.header("X-Session-Token", who.token())
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}

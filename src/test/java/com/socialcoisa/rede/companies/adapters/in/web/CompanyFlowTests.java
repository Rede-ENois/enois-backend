package com.socialcoisa.rede.companies.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * O ciclo inteiro da pagina empresarial com microprojetos: cria pagina, publica microprojeto
 * (entregaveis e criterios de aceite obrigatorios), profissional se candidata, empresa
 * seleciona (IN_PROGRESS) e conclui (COMPLETED). Dono nao se candidata a projeto proprio.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompanyFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void companyMicroProjectFlowEndToEnd() throws Exception {
		var owner = auth("cflow_owner");
		var pro = auth("cflow_pro");
		new TestAuthHelper(mockMvc, objectMapper).activateProfessional(pro);

		MvcResult created = mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cflow-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Estudio Game Dev", "description", "Estudio de jogos indie.",
					"sector", "Tecnologia", "size", "1-10", "location", "Remoto"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.slug").value("estudio-game-dev"))
			.andReturn();
		String companyId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// Falta entregaveis/criterios: recusa.
		mockMvc.perform(post("/empresas/" + companyId + "/microprojetos")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cflow-bad")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Prototipo de menu", "problem", "problema curto demais"))))
			.andExpect(status().isBadRequest());

		MvcResult project = mockMvc.perform(post("/empresas/" + companyId + "/microprojetos")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cflow-project")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Prototipo de menu principal em Unity",
					"problem", "O menu atual traveta em mobile e precisamos de um prototipo navegavel com foco em gamepad.",
					"scope", "Somente a cena de menu; gameplay fora do escopo.",
					"deliverables", "Cena Unity navegavel\nBuild WebGL",
					"acceptanceCriteria", "Navega com gamepad sem travar; roda em WebGL 60fps",
					"budget", "R$ 2.500", "deadline", "2026-12-31"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andReturn();
		String projectId = objectMapper.readTree(project.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/microprojetos?limit=10"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + projectId + "')].status").value("OPEN"));

		MvcResult proposal = mockMvc.perform(post("/microprojetos/" + projectId + "/proposals")
				.header("X-Session-Token", pro.token())
				.header("Idempotency-Key", "cflow-prop-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"message", "Faco isso em uma semana; ja entreguei menus com gamepad em dois jogos."))))
			.andExpect(status().isOk())
			.andReturn();
		String proposalId = objectMapper.readTree(proposal.getResponse().getContentAsString()).get("id").asText();

		// Dono nao se candidata a projeto da propria pagina.
		mockMvc.perform(post("/microprojetos/" + projectId + "/proposals")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cflow-prop-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "candidatura do proprio dono"))))
			.andExpect(status().isForbidden());

		mockMvc.perform(get("/microprojetos/" + projectId)
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.proposals[0].status").value("SENT"));

		mockMvc.perform(post("/microprojetos/" + projectId + "/proposals/" + proposalId + "/select")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"));

		mockMvc.perform(post("/microprojetos/" + projectId + "/complete")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"));

		mockMvc.perform(get("/empresa/estudio-game-dev"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Estudio Game Dev"))
			.andExpect(jsonPath("$.projects[0].status").value("COMPLETED"));
	}

	@Test
	void ownerRoleCannotBeGrantedToAMember() throws Exception {
		var owner = auth("cflow_role_owner");
		var other = auth("cflow_role_other");
		MvcResult created = mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cflow-role-create")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", "Empresa Papeis " + java.util.UUID.randomUUID().toString().substring(0, 8),
					"description", "Pagina para testar papeis.", "sector", "Tecnologia", "size", "1-10",
					"location", "Remoto"))))
			.andExpect(status().isOk())
			.andReturn();
		String companyId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/empresas/" + companyId + "/members")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", other.userId(), "role", "OWNER"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_ROLE"));

		mockMvc.perform(post("/empresas/" + companyId + "/members")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", other.userId(), "role", "SUPERUSER"))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void eachViewerSeesWhereTheyStand() throws Exception {
		var owner = auth("cflow_view_owner");
		var editor = auth("cflow_view_editor");
		var pro = auth("cflow_view_pro");
		new TestAuthHelper(mockMvc, objectMapper).activateProfessional(pro);
		var outsider = auth("cflow_view_out");
		String name = "Empresa Visao " + java.util.UUID.randomUUID().toString().substring(0, 8);
		MvcResult created = mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", name, "description", "Pagina de teste."))))
			.andExpect(status().isOk())
			.andReturn();
		var body = objectMapper.readTree(created.getResponse().getContentAsString());
		String companyId = body.get("id").asText();
		String slug = body.get("slug").asText();
		mockMvc.perform(post("/empresas/" + companyId + "/members")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", editor.userId(), "role", "EDITOR"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.person.id").value(editor.userId()));

		MvcResult project = mockMvc.perform(post("/empresas/" + companyId + "/microprojetos")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Landing page da campanha",
					"problem", "Precisamos de uma landing page simples para a campanha de fim de ano.",
					"deliverables", "Pagina publicada", "acceptanceCriteria", "Carrega em menos de 2s"))))
			.andExpect(status().isOk())
			.andReturn();
		String projectId = objectMapper.readTree(project.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/microprojetos/" + projectId + "/proposals")
				.header("X-Session-Token", pro.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"message", "Entrego em tres dias, ja fiz landing pages de campanha antes."))))
			.andExpect(status().isOk());

		// Who sent a proposal sees it on the card; the team sees the count and who sent it.
		mockMvc.perform(get("/empresa/" + slug).header("X-Session-Token", pro.token()))
			.andExpect(jsonPath("$.projects[0].myProposal").value("SENT"))
			.andExpect(jsonPath("$.projects[0].proposalCount").doesNotExist());
		mockMvc.perform(get("/empresa/" + slug).header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.projects[0].proposalCount").value(1));
		mockMvc.perform(get("/microprojetos/" + projectId).header("X-Session-Token", owner.token()))
			.andExpect(jsonPath("$.proposals[0].person.id").value(pro.userId()));

		// Someone logged in but outside the company reads the project, without the proposals.
		mockMvc.perform(get("/microprojetos/" + projectId).header("X-Session-Token", outsider.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.proposals").isEmpty());

		// A member who is not the owner finds the page among their own; the team stays internal.
		mockMvc.perform(get("/empresas/mine").header("X-Session-Token", editor.token()))
			.andExpect(jsonPath("$[?(@.id == '" + companyId + "')].slug").value(slug));
		mockMvc.perform(get("/empresas/" + companyId + "/members").header("X-Session-Token", outsider.token()))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/empresas/" + companyId + "/members").header("X-Session-Token", editor.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.userId == '" + owner.userId() + "')].person.username").isNotEmpty());
	}

	@Test
	void theAddressKeepsTheLettersOfAnAccentedName() throws Exception {
		var owner = auth("cflow_slug_owner");
		String suffix = java.util.UUID.randomUUID().toString().substring(0, 6);
		mockMvc.perform(post("/empresas")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Estúdio Ação " + suffix))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.slug").value("estudio-acao-" + suffix));
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}

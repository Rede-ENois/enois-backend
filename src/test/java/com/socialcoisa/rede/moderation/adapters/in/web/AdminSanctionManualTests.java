package com.socialcoisa.rede.moderation.adapters.in.web;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Painel do admin sobre penalidades já aplicadas: listar quem está penalizado, remover uma
 * penalidade aplicada errada e corrigir o tipo/motivo sem remover. Remover libera a conta e
 * descarta a denúncia de origem; corrigir para um tipo que fecha acesso derruba as sessões.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminSanctionManualTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@DynamicPropertySource
	static void adminAllowlist(DynamicPropertyRegistry registry) {
		registry.add("rede.admin.emails", () -> "asanc_admin@test.com");
	}

	@Test
	void adminListsRevokesAndEditsASanction() throws Exception {
		var author = new TestAuthHelper(mockMvc, objectMapper).authenticate("asanc_author");
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("asanc_admin");
		String authorToken = author.token();

			// Publica primeiro para saber o username real (o helper de auth cria a conta com
		// nome livre, que não é o identificador que o painel usa).
		MvcResult first = publish(author, "Post do autor penalizado manualmente.", "asanc-first-post");
		String username = objectMapper.readTree(first.getResponse().getContentAsString())
			.get("authorUsername").asText();

		// Restrição direta pelo painel (mesma rota que aplica penalidade manual hoje).
		sanction(username, "RESTRICTION", "primeira");
		String sanctionId = sanctionIdOf(username);

		// A fila do admin lista a sanção com quem levou a penalidade.
		mockMvc.perform(get("/admin/sanctions")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + sanctionId + "')].username").value(username))
			.andExpect(jsonPath("$[?(@.id == '" + sanctionId + "')].status").value("ACTIVE"));

		// Fora do círculo de admin, a fila não existe.
		mockMvc.perform(get("/admin/sanctions")
				.header("X-Session-Token", authorToken))
			.andExpect(status().isForbidden());

		// Remover sem motivo decente: recusa.
		mockMvc.perform(post("/admin/sanctions/" + sanctionId + "/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "curto"))))
			.andExpect(status().isBadRequest());

		// Remoção manual: sanção REMOVED e o autor publica de novo.
		mockMvc.perform(post("/admin/sanctions/" + sanctionId + "/revoke")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"reason", "Aplicacao incorreta: discussao tecnica nao e discriminacao"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sanction.status").value("REMOVED"));

		mockMvc.perform(get("/admin/sanctions?status=REMOVED")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '" + sanctionId + "')].status").value("REMOVED"));

		publish(author, "Voltei depois da correcao manual.", "asanc-freed-post");

		// Denúncia de origem da sanção não existe aqui (aplicação manual), mas a rota edita
		// em cima de uma penalidade viva: restringe de novo e corrige o tipo para SUSPENDED.
		sanction(username, "RESTRICTION", "segunda");
		String second = sanctionIdOf(username);

		mockMvc.perform(post("/admin/sanctions/" + second + "/edit")
				.header("X-Session-Token", admin.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("type", "SUSPENDED",
					"reason", "Correcao para suspensao com prazo, decisao humana revisada"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sanction.type").value("SUSPENDED"))
			.andExpect(jsonPath("$.sanction.status").value("ACTIVE"))
			.andExpect(jsonPath("$.sanction.id").value(second));

		// Tipo que fecha o acesso derruba a sessão na hora.
		mockMvc.perform(get("/me/sanctions").header("X-Session-Token", authorToken))
			.andExpect(status().isUnauthorized());
	}

	private void sanction(String username, String type, String suffix) throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("asanc_admin");
		mockMvc.perform(post("/admin/users/" + username + "/sanction")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "asanc-sanc-" + suffix)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", type, "reason", "Punicao manual de teste do painel"))))
			.andExpect(status().isOk());
	}

	private String sanctionIdOf(String username) throws Exception {
		var admin = new TestAuthHelper(mockMvc, objectMapper).authenticate("asanc_admin");
		MvcResult result = mockMvc.perform(get("/admin/sanctions")
				.header("X-Session-Token", admin.token()))
			.andExpect(status().isOk())

			.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		for (JsonNode row : body) {
			if (username.equals(row.path("username").asText()) && "ACTIVE".equals(row.path("status").asText())) {
				return row.get("id").asText();
			}
		}
		throw new IllegalStateException("sancao ativa nao encontrada para " + username);
	}

	private MvcResult publish(TestAuthHelper.AuthSession session, String content, String idem) throws Exception {
		return mockMvc.perform(post("/feed/posts")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"type", "INSIGHT", "content", content, "tags", List.of(), "mediaIds", List.of()))))
			.andExpect(status().isCreated())
			.andReturn();
	}
}

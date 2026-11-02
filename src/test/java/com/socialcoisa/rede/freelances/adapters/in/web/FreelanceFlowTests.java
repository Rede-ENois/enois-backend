package com.socialcoisa.rede.freelances.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

/** Freela tem tabela própria e exige só faixa de valor, prazo e escopo fechado. */
@SpringBootTest
@AutoConfigureMockMvc
class FreelanceFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void paymentCanBeCashOrDefaultsToFaiscas() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("free_payment");

		mockMvc.perform(post("/freelances")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Site institucional",
					"scope", "Montar o site com cinco páginas e formulário de contato.",
					"budgetMin", 1500,
					"budgetMax", 2500,
					"deadline", LocalDate.now().plusDays(5).toString(),
					"paymentMethod", "CASH"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paymentMethod").value("CASH"));

		// Sem o campo, o freela continua no circuito da faísca (comportamento antigo).
		mockMvc.perform(post("/freelances")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Ajuste de CSS",
					"scope", "Corrigir o layout quebrado na home e no painel administrativo.",
					"budgetMin", 5_000,
					"budgetMax", 8_000,
					"deadline", LocalDate.now().plusDays(5).toString()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paymentMethod").value("FAISCAS"));

		mockMvc.perform(post("/freelances")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Outro trabalho",
					"scope", "Descrição com tamanho suficiente para passar na validação de escopo.",
					"budgetMin", 100,
					"budgetMax", 200,
					"deadline", LocalDate.now().plusDays(5).toString(),
					"paymentMethod", "PIX"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PAYMENT_METHOD"));
	}

	@Test
	void searchesByTitleAndScope() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("free_search");

		mockMvc.perform(post("/freelances")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Landing page de vendas",
					"scope", "Página de vendas com formulário e integração com o checkout.",
					"budgetMin", 20_000,
					"budgetMax", 30_000,
					"deadline", LocalDate.now().plusDays(7).toString()))))
			.andExpect(status().isOk());

		mockMvc.perform(post("/freelances")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Ajuste de banco de dados",
					"scope", "Revisar índices e consultas lentas do banco de produção.",
					"budgetMin", 8_000,
					"budgetMax", 12_000,
					"deadline", LocalDate.now().plusDays(7).toString()))))
			.andExpect(status().isOk());

		mockMvc.perform(get("/freelances").param("q", "banco de dados"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.title == 'Ajuste de banco de dados')]").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.title == 'Landing page de vendas')]").isEmpty());

		mockMvc.perform(get("/freelances").param("q", "nao-existe-em-lugar-nenhum"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void createsListsAndClosesAFreelance() throws Exception {
		var owner = new TestAuthHelper(mockMvc, objectMapper).authenticate("free_owner");

		MvcResult created = mockMvc.perform(post("/freelances")
				.header("X-Session-Token", owner.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Landing page de lançamento",
					"scope", "Entregar uma landing responsiva com formulário de contato e deploy.",
					"budgetMin", 80_000,
					"budgetMax", 150_000,
					"deadline", LocalDate.now().plusDays(10).toString()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("OPEN"))
			.andExpect(jsonPath("$.budgetMin").value(80_000))
			.andReturn();
		String id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asString();

		mockMvc.perform(get("/freelances"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].title").value("Landing page de lançamento"));

		mockMvc.perform(post("/freelances/" + id + "/close").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CLOSED"));

		// A listagem é global (outros testes deixam freelances abertos): afirma só sobre o próprio.
		mockMvc.perform(get("/freelances"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + id + "')]").isEmpty());
	}

	@Test
	void proposalGoesFromTheProfessionalIdentityToTheProfessionalInbox() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var poster = auth.authenticate("free_prop_owner");
		var proposer = auth.authenticate("free_prop_pro");
		MvcResult created = mockMvc.perform(post("/freelances")
				.header("X-Session-Token", poster.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"title", "Landing page",
					"scope", "Uma landing page com formulario e integracao com o CRM.",
					"budgetMin", 800,
					"budgetMax", 1200,
					"deadline", LocalDate.now().plusDays(7).toString()))))
			.andExpect(status().isOk())
			.andReturn();
		String freelanceId = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText();
		String proposalBody = objectMapper.writeValueAsString(Map.of("message", "Entrego em cinco dias."));

		// Sem identidade profissional ativa, nada sai em nome do perfil social.
		mockMvc.perform(post("/freelances/" + freelanceId + "/proposals")
				.header("X-Session-Token", proposer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(proposalBody))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NO_ACTIVE_CORPORATE_PROFILE"));

		String proUsername = auth.activateProfessional(proposer);
		MvcResult proposed = mockMvc.perform(post("/freelances/" + freelanceId + "/proposals")
				.header("X-Session-Token", proposer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(proposalBody))
			.andExpect(status().isOk())
			.andReturn();
		String conversationId = objectMapper.readTree(proposed.getResponse().getContentAsString())
			.path("conversationId").asText();

		// Quem publicou recebe na caixa profissional, vendo a identidade profissional.
		mockMvc.perform(get("/conversations/" + conversationId).header("X-Session-Token", poster.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("FREELANCE"))
			.andExpect(jsonPath("$.context").value("PROFESSIONAL"))
			.andExpect(jsonPath("$.identities." + proposer.userId() + ".username").value(proUsername));
		mockMvc.perform(get("/notifications").param("context", "PROFESSIONAL").header("X-Session-Token", poster.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].kind").value("NEW_WORK_MESSAGE"))
			.andExpect(jsonPath("$.items[0].context").value("PROFESSIONAL"))
			.andExpect(jsonPath("$.items[0].actorUsername").value(proUsername))
			.andExpect(jsonPath("$.items[0].actorIdentity").value("CORPORATE"));
		mockMvc.perform(get("/notifications").param("context", "SOCIAL").header("X-Session-Token", poster.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.kind == 'NEW_WORK_MESSAGE')]").isEmpty());

		// Uma segunda proposta para a mesma pessoa continua na mesma conversa (sem duplicar).
		mockMvc.perform(post("/freelances/" + freelanceId + "/proposals")
				.header("X-Session-Token", proposer.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Posso comecar amanha."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.conversationId").value(conversationId));

		// Quem publicou nao propoe para o proprio freela.
		auth.activateProfessional(poster);
		mockMvc.perform(post("/freelances/" + freelanceId + "/proposals")
				.header("X-Session-Token", poster.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(proposalBody))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CANNOT_PROPOSE_OWN_FREELANCE"));
	}
}

package com.socialcoisa.rede.services.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Os serviços freelance (Fase 5): o pedido exige entregáveis; a proposta precisa estar
 * na faixa; escolher RETÉM as faíscas (escrow); entregar → aceitar libera 90% para o
 * profissional; reembolso devolve 100%. Teto de 5 ofertas abertas por conta.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServicesFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void listingContractDeliverAndAccept() throws Exception {
		var seller = auth("svc_seller");
		var buyer = auth("svc_buyer");
		wallet(seller.token());
		wallet(buyer.token());

		// Publica a oferta por 30 faíscas.
		MvcResult created = mockMvc.perform(post("/servicos/ofertas")
				.header("X-Session-Token", seller.token())
				.header("Idempotency-Key", "svc-listing-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "design", "title", "Logo simples para o seu time",
					"description", "Faço um logo limpo com duas rodadas de ajuste, formato pronto para uso.",
					"deliverables", "SVG + PNG em três tamanhos", "price", 30_000, "deliveryDays", 5))))
			.andExpect(status().isOk())
			.andReturn();
		String listingId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// O vendedor não contrata o próprio serviço.
		mockMvc.perform(post("/servicos/ofertas/" + listingId + "/contratar")
				.header("X-Session-Token", seller.token()))
			.andExpect(status().isBadRequest());

		// O comprador contrata: escrow retém (105.000 -> 75.000, mf).
		MvcResult contract = mockMvc.perform(post("/servicos/ofertas/" + listingId + "/contratar")
				.header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("HELD"))
			.andReturn();
		String contractId = objectMapper.readTree(contract.getResponse().getContentAsString()).get("contractId").asText();
		balance(buyer.token(), 75_000);

		// O vendedor entrega e o comprador aceita: 90% (27) para o vendedor.
		mockMvc.perform(post("/servicos/contratos/" + contractId + "/entregar")
				.header("X-Session-Token", seller.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("note", "SVG e PNG entregues no link X"))))
			.andExpect(status().isOk());
		mockMvc.perform(post("/servicos/contratos/" + contractId + "/aceitar")
				.header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("RELEASED"));
		balance(seller.token(), 132_000);
	}

	@Test
	void cashServiceStaysOutOfTheLedgerAndHasNoFee() throws Exception {
		var seller = auth("svc_cash_seller");
		var buyer = auth("svc_cash_buyer");
		wallet(buyer.token());
		balance(buyer.token(), 105_000);

		// Oferta em REAIS: nenhuma faísca entra em escrow e nenhuma taxa é cobrada.
		MvcResult created = mockMvc.perform(post("/servicos/ofertas")
				.header("X-Session-Token", seller.token())
				.header("Idempotency-Key", "svc-cash-listing")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "design", "title", "Identidade visual completa",
					"description", "Marca, tipografia e manual de aplicação com duas rodadas de ajuste.",
					"deliverables", "PDF do manual + arquivos editáveis", "price", 150_000,
					"currency", "BRL", "deliveryDays", 10))))
			.andExpect(status().isOk())
			.andReturn();
		String listingId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// A oferta existe no catálogo em reais (a criação devolve só id/status).
		mockMvc.perform(get("/servicos"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ofertas[?(@.id == '" + listingId + "')].currency",
				org.hamcrest.Matchers.hasItem("BRL")));

		mockMvc.perform(post("/servicos/ofertas/" + listingId + "/contratar")
				.header("X-Session-Token", buyer.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DIRECT_PAYMENT_REQUIRED"));

		// Saldo intocado: dinheiro não passa pelo ledger de faíscas (logo, sem os 10%).
		balance(buyer.token(), 105_000);
		wallet(seller.token());
		balance(seller.token(), 105_000);
	}

	@Test
	void requestProposalsChooseRefundAndDeliverablesRequired() throws Exception {
		var requester = auth("svc_requester");
		var pro = auth("svc_pro");
		wallet(requester.token());
		wallet(pro.token());

		// Sem entregáveis o pedido nem abre (a lição dos microprojetos).
		mockMvc.perform(post("/servicos/pedidos")
				.header("X-Session-Token", requester.token())
				.header("Idempotency-Key", "svc-req-bad")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "código", "title", "Página para a minha comunidade",
					"description", "Uma página institucional simples com formulário de contato funcional.",
					"budgetMin", 40, "budgetMax", 80))))
			.andExpect(status().isBadRequest());

		// Pedido correto, na faixa 40–80.
		MvcResult created = mockMvc.perform(post("/servicos/pedidos")
				.header("X-Session-Token", requester.token())
				.header("Idempotency-Key", "svc-req-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "código", "title", "Página para a minha comunidade",
					"description", "Uma página institucional simples com formulário de contato funcional.",
					"deliverables", "HTML + CSS publicados", "budgetMin", 40_000, "budgetMax", 80_000))))
			.andExpect(status().isOk())
			.andReturn();
		String requestId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// Proposta fora da faixa é recusada; dentro, aceita.
		mockMvc.perform(post("/servicos/pedidos/" + requestId + "/propostas")
				.header("X-Session-Token", pro.token())
				.header("Idempotency-Key", "svc-prop-bad")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Faço isso rapidinho para você.", "price", 200_000))))
			.andExpect(status().isBadRequest());
		MvcResult proposal = mockMvc.perform(post("/servicos/pedidos/" + requestId + "/propostas")
				.header("X-Session-Token", pro.token())
				.header("Idempotency-Key", "svc-prop-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Entrego em uma semana, com revisão.", "price", 50_000))))
			.andExpect(status().isOk())
			.andReturn();
		String proposalId = objectMapper.readTree(proposal.getResponse().getContentAsString()).get("id").asText();

		// Escolher a proposta: escrow retém (105.000 -> 55.000, mf) e o pedido fecha.
		mockMvc.perform(post("/servicos/pedidos/" + requestId + "/escolher")
				.header("X-Session-Token", requester.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("proposalId", proposalId))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("HELD"));
		balance(requester.token(), 55_000);

		// Reembolso: devolve 100%.
		MvcResult mine = mockMvc.perform(get("/servicos/minhas").header("X-Session-Token", requester.token()))
			.andExpect(status().isOk())
			.andReturn();
		String contractId = objectMapper
			.readTree(mine.getResponse().getContentAsString())
			.at("/contratosComoComprador/0/id").asText();
		mockMvc.perform(post("/servicos/contratos/" + contractId + "/reembolso")
				.header("X-Session-Token", requester.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("REFUNDED"));
		balance(requester.token(), 105_000);
	}

	private void wallet(String token) throws Exception {
		mockMvc.perform(get("/carteira").header("X-Session-Token", token)).andExpect(status().isOk());
	}

	private void balance(String token, long expected) throws Exception {
		MvcResult wallet = mockMvc.perform(get("/carteira").header("X-Session-Token", token)).andExpect(status().isOk())
			.andReturn();
		long balance = objectMapper.readTree(wallet.getResponse().getContentAsString()).get("balance").asLong();
		org.assertj.core.api.Assertions.assertThat(balance)
			.as("saldo: " + wallet.getResponse().getContentAsString()).isEqualTo(expected);
	}

	@Test
	void oServicoMostraQuemPrestaEAbreOChatComAssunto() throws Exception {
		var seller = auth("chat_seller");
		var buyer = auth("chat_buyer");

		MvcResult created = mockMvc.perform(post("/servicos/ofertas")
				.header("X-Session-Token", seller.token())
				.header("Idempotency-Key", "chat-listing-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "Design", "title", "Identidade visual do time",
					"description", "Logo, paleta e manual curto para o time.",
					"deliverables", "Logo + paleta", "price", 79_000, "currency", "BRL", "deliveryDays", 4))))
			.andExpect(status().isOk())
			.andReturn();
		String listingId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// O catalogo mostra QUEM vai prestar (nome e foto) e a moeda do preco.
		mockMvc.perform(get("/servicos"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ofertas[?(@.id == '" + listingId + "')].sellerName").isNotEmpty())
			.andExpect(jsonPath("$.ofertas[?(@.id == '" + listingId + "')].currency").value("BRL"));

		// O chat abre a conversa com o assunto (o tipo do servico e o titulo) e a primeira
		// mensagem e o pedido da contratacao.
		MvcResult chat = mockMvc.perform(post("/servicos/ofertas/" + listingId + "/chat")
				.header("X-Session-Token", buyer.token())
				.header("Idempotency-Key", "chat-open-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Quero esse logo para a minha equipe."))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.assunto").value("Design · Identidade visual do time"))
			.andReturn();
		String conversationId = objectMapper.readTree(chat.getResponse().getContentAsString())
			.get("conversationId").asText();

		// O prestador tem a conversa na caixa de entrada, do tipo SERVICO (a aba "Servicos").
		mockMvc.perform(get("/conversations").header("X-Session-Token", seller.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + conversationId + "')].kind").value("SERVICE"));

		// Clicar de novo no botao NAO abre outra conversa: reaproveita a mesma.
		MvcResult again = mockMvc.perform(post("/servicos/ofertas/" + listingId + "/chat")
				.header("X-Session-Token", buyer.token())
				.header("Idempotency-Key", "chat-open-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "Só confirmando."))))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(objectMapper.readTree(again.getResponse().getContentAsString()).get("conversationId").asText())
			.isEqualTo(conversationId);
	}

	@Test
	void oVendedorNaoAbreChatCom_siProprio() throws Exception {
		var seller = auth("chat_proprio");
		MvcResult created = mockMvc.perform(post("/servicos/ofertas")
				.header("X-Session-Token", seller.token())
				.header("Idempotency-Key", "chat-proprio-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "Design", "title", "Servico do proprio dono",
					"description", "Um servico de teste do proprio dono, para ver o portao.",
					"deliverables", "Entrega em um dia util", "price", 10_000, "deliveryDays", 2))))
			.andExpect(status().isOk())
			.andReturn();
		String listingId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/servicos/ofertas/" + listingId + "/chat")
				.header("X-Session-Token", seller.token())
				.header("Idempotency-Key", "chat-proprio-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("message", "oi"))))
			.andExpect(status().isBadRequest());
	}

	/**
	 * #358: o prestador edita e remove o próprio serviço. Só o dono; o preço trava com contrato
	 * aberto; o removido sai do catálogo mas o contrato que já existe continua.
	 */
	@Test
	void oPrestadorEditaERemoveOProprioServico() throws Exception {
		var seller = auth("edit_seller");
		var outro = auth("edit_outro");
		var buyer = auth("edit_buyer");
		wallet(buyer.token());

		MvcResult created = mockMvc.perform(post("/servicos/ofertas")
				.header("X-Session-Token", seller.token())
				.header("Idempotency-Key", "edit-listing-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "Design", "title", "Logo para o seu time",
					"description", "Um logo limpo com duas rodadas de ajuste e arquivos prontos.",
					"deliverables", "SVG + PNG", "price", 30_000, "deliveryDays", 5))))
			.andExpect(status().isOk())
			.andReturn();
		String listingId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		// Quem não publica não edita nem remove.
		mockMvc.perform(put("/servicos/ofertas/" + listingId)
				.header("X-Session-Token", outro.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "Design", "title", "Titulo trocado por outra pessoa",
					"description", "Descricao longa o bastante para passar da validacao minima.",
					"deliverables", "Nada", "price", 1_000, "deliveryDays", 1))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_THE_SELLER"));
		mockMvc.perform(delete("/servicos/ofertas/" + listingId).header("X-Session-Token", outro.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_THE_SELLER"));

		// O dono edita: o catálogo passa a mostrar o conteúdo novo.
		mockMvc.perform(put("/servicos/ofertas/" + listingId)
				.header("X-Session-Token", seller.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "Design", "title", "Logo e paleta para o seu time",
					"description", "Um logo limpo com duas rodadas de ajuste e a paleta do time.",
					"deliverables", "SVG + PNG + paleta", "price", 30_000, "deliveryDays", 7))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		mockMvc.perform(get("/servicos"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ofertas[?(@.id == '" + listingId + "')].title")
				.value("Logo e paleta para o seu time"))
			.andExpect(jsonPath("$.ofertas[?(@.id == '" + listingId + "')].deliveryDays").value(7));

		// A edição valida como a criação: texto acima do teto da coluna é recusado com o campo.
		mockMvc.perform(put("/servicos/ofertas/" + listingId)
				.header("X-Session-Token", seller.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "Design", "title", "x".repeat(200),
					"description", "Descricao longa o bastante para passar da validacao minima.",
					"deliverables", "SVG", "price", 30_000, "deliveryDays", 5))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SERVICE_FIELD_TOO_LONG"));

		// Com contrato aberto, o preço não muda (as faíscas em escrow são as daquele preço).
		mockMvc.perform(post("/servicos/ofertas/" + listingId + "/contratar")
				.header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk());
		mockMvc.perform(put("/servicos/ofertas/" + listingId)
				.header("X-Session-Token", seller.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"category", "Design", "title", "Logo e paleta para o seu time",
					"description", "Um logo limpo com duas rodadas de ajuste e a paleta do time.",
					"deliverables", "SVG + PNG + paleta", "price", 90_000, "deliveryDays", 7))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SERVICE_HAS_OPEN_CONTRACTS"));

		// O dono remove: sai do catálogo, e o contrato aberto continua no painel do comprador.
		mockMvc.perform(delete("/servicos/ofertas/" + listingId).header("X-Session-Token", seller.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));
		mockMvc.perform(get("/servicos"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ofertas[?(@.id == '" + listingId + "')]").isEmpty());
		mockMvc.perform(get("/servicos/minhas").header("X-Session-Token", buyer.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contratosComoComprador[?(@.listingId == '" + listingId + "')]").isNotEmpty());
	}

	private TestAuthHelper.AuthSession auth(String name) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(name);
	}
}

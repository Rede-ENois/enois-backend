package com.socialcoisa.rede.groups;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ciclo de vida e ingresso do grupo, de ponta a ponta.
 *
 * O que este teste tranca:
 *
 * - criar o grupo coloca quem criou como dono, e a descoberta pública mostra só a apresentação;
 * - entrada ABERTA vira membro na hora; entrada por APROVAÇÃO deixa o pedido pendente até a
 *   decisão — e a mesma pessoa não consegue dois pendentes;
 * - aprovar duas vezes não cria duas associações (a segunda decisão recebe 409);
 * - quem não administra não edita, e quem não é membro não vê a lista de membros;
 * - o dono não sai sem transferir a propriedade; depois de transferir, sai.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GroupFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper authHelper() {
		return new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String name, String joinMode, String idemKey)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/groups")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", idemKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"name", name,
					"description", "grupo de teste",
					"joinMode", joinMode))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	private MvcResult join(TestAuthHelper.AuthSession user, String groupId) throws Exception {
		return mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", user.token())
				.header("Idempotency-Key", "join-" + groupId + "-" + user.userId()))
			.andReturn();
	}

	@Test
	void criarColocaODonoEDescobertaMostraSoAApresentacao() throws Exception {
		var dono = authHelper().authenticate("grupo-dono");
		String groupId = createGroup(dono, "Turma do Café", "APPROVAL", "create-turma-do-cafe");

		// A página do grupo (pública) mostra o dono como membro.
		mockMvc.perform(get("/groups/turma-do-cafe").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(groupId))
			.andExpect(jsonPath("$.joined").value(true))
			.andExpect(jsonPath("$.viewerRole").value("OWNER"))
			.andExpect(jsonPath("$.members").value(1));

		// A descoberta é pública e lista o grupo; sem sessão, ninguém aparece como membro.
		mockMvc.perform(get("/groups").param("query", "café"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].slug").value("turma-do-cafe"))
			.andExpect(jsonPath("$.items[0].joined").value(false))
			.andExpect(jsonPath("$.items[0].viewerRole").doesNotExist());

		// /groups/mine devolve os grupos em que o usuário participa.
		mockMvc.perform(get("/groups/mine").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(groupId))
			.andExpect(jsonPath("$.items[0].joined").value(true))
			.andExpect(jsonPath("$.items[0].viewerRole").value("OWNER"));

		// /groups/mine sem sessão é 401.
		mockMvc.perform(get("/groups/mine"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void entradaAbertaViraMembroNaHora() throws Exception {
		var dono = authHelper().authenticate("grupo-aberto-dono");
		var pessoa = authHelper().authenticate("grupo-aberto-pessoa");
		String groupId = createGroup(dono, "Grupo Aberto", "OPEN", "create-grupo-aberto");

		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "join-aberto-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("MEMBER"));

		mockMvc.perform(get("/groups/grupo-aberto").header("X-Session-Token", pessoa.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.joined").value(true))
			.andExpect(jsonPath("$.viewerRole").value("MEMBER"))
			.andExpect(jsonPath("$.members").value(2));

		// Entrar de novo é 409, não duplica associação.
		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "join-aberto-2"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
	}

	@Test
	void entradaPorAprovacaoFicaPendenteEAprovaUmaVezSo() throws Exception {
		var dono = authHelper().authenticate("grupo-aprov-dono");
		var pessoa = authHelper().authenticate("grupo-aprov-pessoa");
		String groupId = createGroup(dono, "Grupo Fechado", "APPROVAL", "create-grupo-fechado");

		join(pessoa, groupId);
		MvcResult pendente = mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "join-fechado-2"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REQUEST_ALREADY_PENDING"))
			.andReturn();
		assertThat(pendente.getResponse().getContentAsString()).contains("REQUEST_ALREADY_PENDING");

		// A pessoa ainda não é membro, mas a página sabe que o pedido está pendente.
		mockMvc.perform(get("/groups/grupo-fechado").header("X-Session-Token", pessoa.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.joined").value(false))
			.andExpect(jsonPath("$.requestPending").value(true));

		// Quem administra vê o pedido.
		MvcResult lista = mockMvc.perform(get("/groups/" + groupId + "/requests")
				.header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andReturn();
		String requestId = objectMapper.readTree(lista.getResponse().getContentAsString())
			.get("items").get(0).get("id").asText();

		mockMvc.perform(post("/groups/" + groupId + "/requests/" + requestId + "/approve")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "aprova-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));

		// Aprovar de novo não cria segunda associação.
		mockMvc.perform(post("/groups/" + groupId + "/requests/" + requestId + "/approve")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "aprova-2"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));

		mockMvc.perform(get("/groups/grupo-fechado").header("X-Session-Token", pessoa.token()))
			.andExpect(jsonPath("$.joined").value(true))
			.andExpect(jsonPath("$.members").value(2));
	}

	@Test
	void quemNaoAdministraNaoEditaENaoVeMembros() throws Exception {
		var dono = authHelper().authenticate("grupo-regra-dono");
		var pessoa = authHelper().authenticate("grupo-regra-pessoa");
		var estranho = authHelper().authenticate("grupo-regra-estranho");
		String groupId = createGroup(dono, "Grupo Regras", "OPEN", "create-grupo-regras");

		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "join-regras-1"))
			.andExpect(status().isOk());

		// Membro comum não edita.
		mockMvc.perform(patch("/groups/" + groupId)
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "patch-regras-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("name", "Nome trocado"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		// Quem não é membro não vê a lista de membros (a autorização é no backend).
		mockMvc.perform(get("/groups/" + groupId + "/members").header("X-Session-Token", estranho.token()))
			.andExpect(status().isForbidden());

		// O dono vê.
		mockMvc.perform(get("/groups/" + groupId + "/members").header("X-Session-Token", dono.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2));
	}

	@Test
	void oDonoNaoSaiSemTransferirAPropriedade() throws Exception {
		var dono = authHelper().authenticate("grupo-dono-sai");
		var pessoa = authHelper().authenticate("grupo-dono-pessoa");
		String groupId = createGroup(dono, "Grupo Saida", "OPEN", "create-grupo-saida");

		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "join-saida-1"))
			.andExpect(status().isOk());

		mockMvc.perform(delete("/groups/" + groupId + "/members/me")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "leave-saida-1"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("OWNERSHIP_TRANSFER_REQUIRED"));

		mockMvc.perform(post("/groups/" + groupId + "/transfer")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "transfer-saida-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", pessoa.userId()))))
			.andExpect(status().isOk());

		// Agora o antigo dono é ADMIN e consegue sair; o novo dono fica.
		mockMvc.perform(delete("/groups/" + groupId + "/members/me")
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "leave-saida-2"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/groups/grupo-saida").header("X-Session-Token", pessoa.token()))
			.andExpect(jsonPath("$.viewerRole").value("OWNER"))
			.andExpect(jsonPath("$.members").value(1));
	}

	@Test
	void removerMembroExigePermissaoENaoRemoveODono() throws Exception {
		var dono = authHelper().authenticate("grupo-rem-dono");
		var pessoa = authHelper().authenticate("grupo-rem-pessoa");
		String groupId = createGroup(dono, "Grupo Remocao", "OPEN", "create-grupo-remocao");

		mockMvc.perform(post("/groups/" + groupId + "/join")
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "join-rem-1"))
			.andExpect(status().isOk());

		// Membro comum não remove ninguém.
		mockMvc.perform(delete("/groups/" + groupId + "/members/" + dono.userId())
				.header("X-Session-Token", pessoa.token())
				.header("Idempotency-Key", "remove-rem-1"))
			.andExpect(status().isForbidden());

		// O dono não pode ser removido.
		mockMvc.perform(delete("/groups/" + groupId + "/members/" + dono.userId())
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "remove-rem-2"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CANNOT_REMOVE_OWNER"));

		// O dono remove o membro comum.
		mockMvc.perform(delete("/groups/" + groupId + "/members/" + pessoa.userId())
				.header("X-Session-Token", dono.token())
				.header("Idempotency-Key", "remove-rem-3"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/groups/" + groupId + "/members").header("X-Session-Token", dono.token()))
			.andExpect(jsonPath("$.items.length()").value(1));

		// Removido, o acesso ao conteúdo privado é revogado na hora.
		mockMvc.perform(get("/groups/" + groupId + "/members").header("X-Session-Token", pessoa.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void nomesIguaisGanhamEnderecosDiferentes() throws Exception {
		var dono = authHelper().authenticate("grupo-slug-dono");
		createGroup(dono, "Programação Funcional", "OPEN", "create-slug-1");
		createGroup(dono, "Programação Funcional", "OPEN", "create-slug-2");

		JsonNode lista = objectMapper.readTree(mockMvc.perform(get("/groups").param("query", "programação"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
		assertThat(lista.get("items").size()).isEqualTo(2);
		assertThat(lista.get("items").get(0).get("slug").asText())
			.isNotEqualTo(lista.get("items").get(1).get("slug").asText());
	}
}

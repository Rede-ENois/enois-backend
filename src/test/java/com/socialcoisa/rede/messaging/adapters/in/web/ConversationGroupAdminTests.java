package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Painel de admin do grupo de conversa (issue #915): dono, administrador promovido, quem entra
 * depois e ve o historico, e quem sai e perde o acesso.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationGroupAdminTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private String createGroup(TestAuthHelper.AuthSession owner, String idem, List<String> participants)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "GROUP", "title", "Grupo de teste",
					"participantIds", participants))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void onlyTheOwnerAndAdminsCanAddPeople() throws Exception {
		var owner = auth.authenticate("cga_owner");
		var member = auth.authenticate("cga_member");
		var newcomer = auth.authenticate("cga_newcomer");
		var other = auth.authenticate("cga_other");
		String conversationId = createGroup(owner, "cga-add-1", List.of(member.userId()));

		// Membro comum nao convida: o painel e de quem administra.
		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants")
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "cga-add-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", newcomer.userId()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GROUP_ADMIN_REQUIRED"));

		// Quem nao participa do grupo nem enxerga o painel.
		mockMvc.perform(get("/conversations/" + conversationId + "/group/participants")
				.header("X-Session-Token", other.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));

		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga-add-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", newcomer.userId()))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.userId").value(newcomer.userId()))
			.andExpect(jsonPath("$.role").value("MEMBER"));

		// Quem entrou ve a conversa na propria caixa.
		mockMvc.perform(get("/conversations/" + conversationId)
				.header("X-Session-Token", newcomer.token()))
			.andExpect(status().isOk());
	}

	@Test
	void aPromotedAdminCanAddAndRemoveMembersButNotOtherAdmins() throws Exception {
		var owner = auth.authenticate("cga2_owner");
		var admin = auth.authenticate("cga2_admin");
		var member = auth.authenticate("cga2_member");
		var secondAdmin = auth.authenticate("cga2_admin2");
		var newcomer = auth.authenticate("cga2_newcomer");
		String conversationId = createGroup(owner, "cga2-1",
			List.of(admin.userId(), secondAdmin.userId(), member.userId()));

		// Um membro comum nao promove ninguem, nem a si mesmo.
		mockMvc.perform(patch("/conversations/" + conversationId + "/group/participants/" + admin.userId())
				.header("X-Session-Token", member.token())
				.header("Idempotency-Key", "cga2-role-0")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("role", "ADMIN"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GROUP_OWNER_REQUIRED"));

		mockMvc.perform(patch("/conversations/" + conversationId + "/group/participants/" + admin.userId())
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga2-role-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("role", "ADMIN"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("ADMIN"));

		mockMvc.perform(patch("/conversations/" + conversationId + "/group/participants/" + secondAdmin.userId())
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga2-role-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("role", "ADMIN"))))
			.andExpect(status().isOk());

		// O administrador convida.
		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "cga2-add-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", newcomer.userId()))))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants")
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "cga2-add-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", newcomer.userId()))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ALREADY_PARTICIPANT"));

		// E remove membro comum.
		mockMvc.perform(delete("/conversations/" + conversationId + "/group/participants/" + member.userId())
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "cga2-remove-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REMOVED"));

		// Mas nao mexe em outro administrador.
		mockMvc.perform(delete("/conversations/" + conversationId + "/group/participants/" + secondAdmin.userId())
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "cga2-remove-2"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GROUP_ADMIN_PROTECTED"));

		// Nem o dono sai do proprio grupo.
		mockMvc.perform(delete("/conversations/" + conversationId + "/group/participants/" + owner.userId())
				.header("X-Session-Token", admin.token())
				.header("Idempotency-Key", "cga2-remove-3"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GROUP_OWNER_PROTECTED"));

		// E o dono remove o administrador.
		mockMvc.perform(delete("/conversations/" + conversationId + "/group/participants/" + secondAdmin.userId())
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga2-remove-4"))
			.andExpect(status().isOk());
	}

	@Test
	void aRemovedMemberLosesTheConversation() throws Exception {
		var owner = auth.authenticate("cga3_owner");
		var member = auth.authenticate("cga3_member");
		String conversationId = createGroup(owner, "cga3-1", List.of(member.userId()));
		storeCiphertext(owner, conversationId, Map.of(owner.userId(), "k-owner", member.userId(), "k-member"));

		mockMvc.perform(delete("/conversations/" + conversationId + "/group/participants/" + member.userId())
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga3-remove-1"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/conversations/" + conversationId)
				.header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
		mockMvc.perform(get("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
		mockMvc.perform(get("/conversations")
				.header("X-Session-Token", member.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(conversationId)).isEmpty());
		// Quem ficou continua com a conversa e o historico.
		mockMvc.perform(get("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", owner.token()))
			.andExpect(status().isOk());
	}

	@Test
	void theLateJoinerGetsTheKeyOfEveryEarlierMessage() throws Exception {
		var owner = auth.authenticate("cga4_owner");
		var member = auth.authenticate("cga4_member");
		var newcomer = auth.authenticate("cga4_newcomer");
		String conversationId = createGroup(owner, "cga4-1", List.of(member.userId()));
		String first = storeCiphertext(owner, conversationId, Map.of(owner.userId(), "k-owner-1",
			member.userId(), "k-member-1"));
		String second = storeCiphertext(owner, conversationId, Map.of(owner.userId(), "k-owner-2",
			member.userId(), "k-member-2"));

		// Antes de entrar, quem foi convidado nao tem chave nenhuma do historico.
		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga4-add-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", newcomer.userId()))))
			.andExpect(status().isCreated());

		// So quem administra concede historico.
		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants/" + newcomer.userId()
				+ "/history-keys")
				.header("X-Session-Token", newcomer.token())
				.header("Idempotency-Key", "cga4-keys-0")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("keys", Map.of(first, "k-newcomer-1")))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("GROUP_ADMIN_REQUIRED"));

		String body = objectMapper.writeValueAsString(Map.of("keys",
			Map.of(first, "k-newcomer-1", second, "k-newcomer-2")));
		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants/" + newcomer.userId()
				+ "/history-keys")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga4-keys-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.granted").value(2));

		// A concessao fica no mapa da mensagem e nao apaga as chaves de quem ja estava la.
		var messages = mockMvc.perform(get("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", newcomer.token()))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		var items = objectMapper.readTree(messages).get("items");
		var keysOfNewcomer = new java.util.HashMap<String, String>();
		for (var item : items) {
			var encryptedKeys = objectMapper.readValue(item.get("encryptedKeys").asText(),
				new tools.jackson.core.type.TypeReference<Map<String, String>>() {
				});
			keysOfNewcomer.put(item.get("id").asText(), encryptedKeys.get(newcomer.userId()));
			assertThat(encryptedKeys).containsKeys(owner.userId(), member.userId());
		}
		assertThat(keysOfNewcomer.get(first)).isEqualTo("k-newcomer-1");
		assertThat(keysOfNewcomer.get(second)).isEqualTo("k-newcomer-2");

		// Repetir a concessao nao escreve de novo.
		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants/" + newcomer.userId()
				+ "/history-keys")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga4-keys-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.granted").value(0));
	}

	@Test
	void historyKeysOfAnotherConversationAreRefused() throws Exception {
		var owner = auth.authenticate("cga5_owner");
		var member = auth.authenticate("cga5_member");
		var otherOwner = auth.authenticate("cga5_other_owner");
		var otherMember = auth.authenticate("cga5_other_member");
		String conversationId = createGroup(owner, "cga5-1", List.of(member.userId()));
		String otherConversation = createGroup(otherOwner, "cga5-2", List.of(otherMember.userId()));
		String foreignMessage = storeCiphertext(otherOwner, otherConversation,
			Map.of(otherOwner.userId(), "k-other-owner", otherMember.userId(), "k-other-member"));

		mockMvc.perform(post("/conversations/" + conversationId + "/group/participants/" + member.userId()
				+ "/history-keys")
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", "cga5-keys-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("keys", Map.of(foreignMessage, "k-member")))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));
	}

	@Test
	void theAdminPanelIsOnlyForGroups() throws Exception {
		var a = auth.authenticate("cga6_a");
		var b = auth.authenticate("cga6_b");
		MvcResult dm = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "cga6-dm")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isOk())
			.andReturn();
		String directId = objectMapper.readTree(dm.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/conversations/" + directId + "/group/participants")
				.header("X-Session-Token", a.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CONVERSATION_KIND"));
	}

	/**
	 * Grava uma mensagem E2E ja cifrada para o teste. O servidor nao gera chave: quem cifra e o
	 * aparelho de quem envia, e o teste faz o papel dele — so o mapa de chaves por participante
	 * importa aqui (o IV e opaco para o servidor).
	 */
	private String storeCiphertext(TestAuthHelper.AuthSession sender, String conversationId,
			Map<String, String> keys) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", sender.token())
				.header("Idempotency-Key", "e2e-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"text", "ciphertext-" + UUID.randomUUID(),
					"mediaIds", List.of(),
					"e2e", true,
					"iv", "aXYtZGUtdGVzdGU=",
					"encryptedKeys", objectMapper.writeValueAsString(keys)))))
			.andExpect(status().isCreated())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

}


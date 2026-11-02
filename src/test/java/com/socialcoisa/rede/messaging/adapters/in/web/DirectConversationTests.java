package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Botao "Mensagem" do perfil: precisa abrir a conversa direta com a pessoa, reaproveitando a
 * existente em vez de criar duplicatas.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DirectConversationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void opensAndReusesTheSameDirectConversation() throws Exception {
		var a = auth.authenticate("dm_user_a");
		var b = auth.authenticate("dm_user_b");

		String first = openDirect(a, b.userId(), "dm-a-1");
		assertThat(first).isNotBlank();

		// Abrir de novo nao pode criar uma segunda conversa.
		assertThat(openDirect(a, b.userId(), "dm-a-2")).isEqualTo(first);
		// Na ordem inversa tambem e a mesma conversa.
		assertThat(openDirect(b, a.userId(), "dm-b-1")).isEqualTo(first);
	}

	@Test
	void startingADirectMessageFromTheInboxSearchReusesTheSameConversation() throws Exception {
		var a = auth.authenticate("dm_search_a");
		var b = auth.authenticate("dm_search_b");
		String direct = openDirect(a, b.userId(), "dm-search-1");

		// O "nova conversa" da caixa usa POST /conversations: antes criava uma conversa nova a cada vez.
		mockMvc.perform(post("/conversations")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-search-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "DIRECT", "participantIds",
					java.util.List.of(b.userId())))))
			.andExpect(jsonPath("$.id").value(direct))
			.andExpect(jsonPath("$.context").value("SOCIAL"));
	}

	@Test
	void inboxFiltersByContextAndRejectsAnUnknownOne() throws Exception {
		var a = auth.authenticate("dm_ctx_a");
		var b = auth.authenticate("dm_ctx_b");
		String direct = openDirect(a, b.userId(), "dm-ctx-1");

		mockMvc.perform(get("/conversations").param("context", "SOCIAL").header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + direct + "')].context").value(org.hamcrest.Matchers.hasItem("SOCIAL")));
		mockMvc.perform(get("/conversations").param("context", "PROFESSIONAL").header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + direct + "')]").isEmpty());
		// Sem contexto, a lista continua completa (contrato dos clientes antigos).
		mockMvc.perform(get("/conversations").header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[?(@.id == '" + direct + "')]").isNotEmpty());
		mockMvc.perform(get("/conversations/unread-count").param("context", "PROFESSIONAL")
				.header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").isNumber());
		mockMvc.perform(get("/conversations").param("context", "WORK").header("X-Session-Token", a.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_CONTEXT"));
	}

	@Test
	void rejectsConversationWithSelf() throws Exception {
		var a = auth.authenticate("dm_self");
		mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-self-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", a.userId()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_RECIPIENT"));
	}

	@Test
	void rejectsConversationWithBlockedUser() throws Exception {
		var a = auth.authenticate("dm_blocker");
		var b = auth.authenticate("dm_blocked");

		mockMvc.perform(post("/network/blocks/" + b.userId())
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-block-1"))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-blocked-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("USER_BLOCKED"));
	}

	@Test
	void newMessageMovesConversationToTheTop() throws Exception {
		var a = auth.authenticate("dm_top_a");
		var b = auth.authenticate("dm_top_b");
		var c = auth.authenticate("dm_top_c");

		String ab = openDirect(a, b.userId(), "dm-top-ab");
		String ac = openDirect(a, c.userId(), "dm-top-ac");
		assertThat(ab).isNotEqualTo(ac);

		mockMvc.perform(post("/conversations/" + ab + "/messages")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-top-send")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "mensagem nova"))))
			.andExpect(status().isCreated());

		MvcResult list = mockMvc.perform(get("/conversations").header("X-Session-Token", a.token()))
			.andExpect(status().isOk()).andReturn();
		var items = objectMapper.readTree(list.getResponse().getContentAsString()).get("items");
		assertThat(items.get(0).get("id").asText()).isEqualTo(ab);
	}

	private String openDirect(TestAuthHelper.AuthSession session, String targetUserId, String idem) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", idem)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", targetUserId))))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}
}
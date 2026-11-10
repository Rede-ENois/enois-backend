package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
 * "Apagar para mim" tem de valer na CAIXA DE ENTRADA, não só na conversa aberta.
 *
 * <p>O filtro de mensagem apagada existia apenas em {@code /conversations/{id}/messages}. A lista
 * de conversas monta a prévia pela última mensagem da conversa e o badge de não lidas pela contagem
 * posterior ao último read — nenhum dos dois consultava {@code message_hidden}. Resultado: a pessoa
 * apagava a mensagem, abria a conversa e ela não estava lá, mas a caixa de entrada continuava
 * mostrando o texto apagado como prévia e contando aquela mensagem no badge.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class HiddenMessageInboxTests {

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
	void hiddenMessageLeavesTheInboxPreview() throws Exception {
		var a = auth.authenticate("hide_inbox_prev_a");
		var b = auth.authenticate("hide_inbox_prev_b");
		String conversationId = createConversation(a, b.userId());
		send(a, conversationId, "primeira mensagem");
		String apagada = send(a, conversationId, "texto que a pessoa apagou para si");

		hide(b, conversationId, apagada);

		JsonNode inbox = inbox(b);
		JsonNode conversa = conversationIn(inbox, conversationId);
		assertThat(conversa).as("a conversa continua na caixa de entrada").isNotNull();
		assertThat(conversa.path("lastMessage").asString())
			.as("a previa nao pode ser a mensagem que a pessoa apagou")
			.isNotEqualTo("texto que a pessoa apagou para si");
	}

	@Test
	void hiddenMessageLeavesTheUnreadBadge() throws Exception {
		var a = auth.authenticate("hide_inbox_badge_a");
		var b = auth.authenticate("hide_inbox_badge_b");
		String conversationId = createConversation(a, b.userId());
		String apagada = send(a, conversationId, "mensagem que sera apagada");

		// B ainda nao leu nada: a mensagem conta no badge.
		assertThat(unreadOf(b, conversationId)).as("antes de apagar, conta").isEqualTo(1);

		hide(b, conversationId, apagada);

		assertThat(unreadOf(b, conversationId))
			.as("mensagem apagada para mim nao pode continuar no badge de nao lidas")
			.isZero();
	}

	@Test
	void hiddenMessageLeavesTheTotalUnreadCount() throws Exception {
		var a = auth.authenticate("hide_inbox_total_a");
		var b = auth.authenticate("hide_inbox_total_b");
		String conversationId = createConversation(a, b.userId());
		String apagada = send(a, conversationId, "mensagem apagada do total");

		assertThat(totalUnread(b)).as("antes de apagar, conta no total").isEqualTo(1);

		hide(b, conversationId, apagada);

		assertThat(totalUnread(b))
			.as("o contador global tambem respeita o apagar para mim")
			.isZero();
	}

	@Test
	void hidingOnlyAffectsThePersonWhoAsked() throws Exception {
		var a = auth.authenticate("hide_inbox_scope_a");
		var b = auth.authenticate("hide_inbox_scope_b");
		String conversationId = createConversation(a, b.userId());
		String mensagem = send(a, conversationId, "mensagem visivel para o outro");

		hide(b, conversationId, mensagem);

		// Para A (que nao apagou) a previa e o badge continuam.
		JsonNode conversa = conversationIn(inbox(a), conversationId);
		assertThat(conversa.path("lastMessage").asString()).isEqualTo("mensagem visivel para o outro");
	}

	private int unreadOf(TestAuthHelper.AuthSession session, String conversationId) throws Exception {
		JsonNode conversa = conversationIn(inbox(session), conversationId);
		return conversa == null ? -1 : conversa.path("unread").asInt();
	}

	private int totalUnread(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(get("/conversations/unread-count")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).path("count").asInt();
	}

	private JsonNode inbox(TestAuthHelper.AuthSession session) throws Exception {
		MvcResult result = mockMvc.perform(get("/conversations").param("limit", "50")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
	}

	private JsonNode conversationIn(JsonNode items, String conversationId) {
		for (int i = 0; i < items.size(); i++) {
			if (conversationId.equals(items.get(i).path("id").asString())) {
				return items.get(i);
			}
		}
		return null;
	}

	private void hide(TestAuthHelper.AuthSession session, String conversationId, String messageId) throws Exception {
		mockMvc.perform(post("/conversations/" + conversationId + "/messages/hide")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "hide-inbox-" + session.userId() + "-" + messageId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("ids", List.of(messageId)))))
			.andExpect(status().isOk());
	}

	private String send(TestAuthHelper.AuthSession session, String conversationId, String text) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", "hide-inbox-send-" + session.userId() + "-" + text.hashCode())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", text))))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String createConversation(TestAuthHelper.AuthSession a, String otherId) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "hide-inbox-conv-" + a.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "DIRECT", "participantIds", List.of(otherId)))))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}
}
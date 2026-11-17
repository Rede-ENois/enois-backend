package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Clicar em enviar duas vezes não cria duas mensagens idênticas. */
@SpringBootTest
@AutoConfigureMockMvc
class DuplicateMessageTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void repeatedMessageReturnsTheSameRow() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("dup_msg_a");
		var b = auth.authenticate("dup_msg_b");

		MvcResult conversation = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dup-msg-direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String id = objectMapper.readTree(conversation.getResponse().getContentAsString()).get("id").asText();

		MvcResult first = send(a.token(), id, "mesma mensagem", "dup-msg-1");
		MvcResult second = send(a.token(), id, "mesma mensagem", "dup-msg-2");
		String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
		String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
		assertThat(secondId).isEqualTo(firstId);

		MvcResult list = mockMvc.perform(get("/conversations/" + id + "/messages")
				.header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andReturn();
		var items = objectMapper.readTree(list.getResponse().getContentAsString()).get("items");
		assertThat(items.size()).isEqualTo(1);
	}

	/**
	 * O reply participa do dedupe: o mesmo texto citando mensagens diferentes são dois envios
	 * (o contexto do segundo não colapsa no primeiro); repetir o MESMO reply continua sendo
	 * retry e devolve a linha original.
	 */
	@Test
	void sameTextReplyingToDifferentMessagesCreatesDistinctRows() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("dup_reply_a");
		var b = auth.authenticate("dup_reply_b");

		MvcResult conversation = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dup-reply-direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String id = objectMapper.readTree(conversation.getResponse().getContentAsString()).get("id").asText();

		String anchor1 = objectMapper.readTree(send(a.token(), id, "primeira âncora", "dup-reply-anchor-1")
				.getResponse().getContentAsString()).get("id").asText();
		String anchor2 = objectMapper.readTree(send(a.token(), id, "segunda âncora", "dup-reply-anchor-2")
				.getResponse().getContentAsString()).get("id").asText();

		MvcResult reply1 = sendReply(a.token(), id, "concordo", anchor1, "dup-reply-1");
		MvcResult reply2 = sendReply(a.token(), id, "concordo", anchor2, "dup-reply-2");
		String reply1Id = objectMapper.readTree(reply1.getResponse().getContentAsString()).get("id").asText();
		String reply2Id = objectMapper.readTree(reply2.getResponse().getContentAsString()).get("id").asText();
		assertThat(reply2Id).isNotEqualTo(reply1Id);

		// O mesmo reply repetido (retry/duplo clique) continua devolvendo a linha original.
		MvcResult retry = sendReply(a.token(), id, "concordo", anchor1, "dup-reply-3");
		assertThat(objectMapper.readTree(retry.getResponse().getContentAsString()).get("id").asText())
			.isEqualTo(reply1Id);

		MvcResult list = mockMvc.perform(get("/conversations/" + id + "/messages")
				.header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andReturn();
		var items = objectMapper.readTree(list.getResponse().getContentAsString()).get("items");
		assertThat(items.size()).isEqualTo(4);
	}

	private MvcResult send(String token, String conversationId, String text, String key) throws Exception {
		return mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", text))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
	}

	private MvcResult sendReply(String token, String conversationId, String text, String replyToId,
			String key) throws Exception {
		return mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", text, "replyToId", replyToId))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
	}
}

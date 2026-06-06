package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class MessagingContractControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void createsConversationSendsMessageAndMarksRead() throws Exception {
		String createPayload = objectMapper.writeValueAsString(Map.of(
			"kind", "DIRECT",
			"participantIds", List.of("msg_user_b")));

		MvcResult created = mockMvc.perform(post("/api/conversations")
				.header("X-User-Id", "msg_user_a")
				.header("Idempotency-Key", "msg-create-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(createPayload))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.kind").value("DIRECT"))
			.andReturn();

		String conversationId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

		MvcResult msgResult = mockMvc.perform(post("/api/conversations/{id}/messages", conversationId)
				.header("X-User-Id", "msg_user_a")
				.header("Idempotency-Key", "msg-send-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "Ola, tudo bem?"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.text").value("Ola, tudo bem?"))
			.andExpect(jsonPath("$.from").value("msg_user_a"))
			.andReturn();

		String messageId = objectMapper.readTree(msgResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(get("/api/conversations/{id}", conversationId)
				.header("X-User-Id", "msg_user_a"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lastMessage").value("Ola, tudo bem?"));

		mockMvc.perform(post("/api/conversations/{id}/read", conversationId)
				.header("X-User-Id", "msg_user_b")
				.header("Idempotency-Key", "msg-read-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("READ"));

		mockMvc.perform(get("/api/conversations/{id}/messages", conversationId)
				.header("X-User-Id", "msg_user_a"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(messageId));
	}

	@Test
	void replaysIdempotentMessageSend() throws Exception {
		String createPayload = objectMapper.writeValueAsString(Map.of(
			"kind", "DIRECT",
			"participantIds", List.of("msg_user_d")));

		MvcResult conv = mockMvc.perform(post("/api/conversations")
				.header("X-User-Id", "msg_user_c")
				.header("Idempotency-Key", "msg-create-idem")
				.contentType(MediaType.APPLICATION_JSON)
				.content(createPayload))
			.andExpect(status().isCreated())
			.andReturn();

		String cid = objectMapper.readTree(conv.getResponse().getContentAsString()).get("id").asText();
		String msgPayload = objectMapper.writeValueAsString(Map.of("text", "Idempotente"));

		MvcResult first = mockMvc.perform(post("/api/conversations/{id}/messages", cid)
				.header("X-User-Id", "msg_user_c")
				.header("Idempotency-Key", "msg-send-idem")
				.contentType(MediaType.APPLICATION_JSON)
				.content(msgPayload))
			.andExpect(status().isCreated())
			.andReturn();

		MvcResult replay = mockMvc.perform(post("/api/conversations/{id}/messages", cid)
				.header("X-User-Id", "msg_user_c")
				.header("Idempotency-Key", "msg-send-idem")
				.contentType(MediaType.APPLICATION_JSON)
				.content(msgPayload))
			.andExpect(status().isCreated())
			.andReturn();

		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
	}
}

package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.socialcoisa.rede.messaging.application.MessagingService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Nenhuma rota que abre conversa aceita um destinatário que não existe, que foi excluído ou que
 * está banido — e a recusa não deixa conversa criada para trás.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConversationRecipientErrorsTests {

	private static final List<String> OPENERS = List.of("/conversations/direct", "/conversations/business-direct");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MessagingService messagingService;

	@Test
	void unknownRecipientIs404OnEveryOpeningRoute() throws Exception {
		var a = new TestAuthHelper(mockMvc, objectMapper).authenticate("recip_unknown");
		new TestAuthHelper(mockMvc, objectMapper).activateProfessional(a);

		for (String route : OPENERS) {
			mockMvc.perform(post(route)
					.header("X-Session-Token", a.token())
					.header("Idempotency-Key", "recip-unknown-" + route.replace('/', '-'))
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("userId", "user_0000000000000000"))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
		}
	}

	@Test
	void malformedRecipientIs400() throws Exception {
		var a = new TestAuthHelper(mockMvc, objectMapper).authenticate("recip_malformed");

		mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "recip-malformed-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", "../conversation_x"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_RECIPIENT"));
	}

	@Test
	void genericConversationWithUnknownParticipantWritesNothing() throws Exception {
		var a = new TestAuthHelper(mockMvc, objectMapper).authenticate("recip_generic");

		mockMvc.perform(post("/conversations")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "recip-generic-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "GROUP", "participantIds",
					List.of("user_0000000000000001")))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

		// O grupo não nasceu: a recusa acontece antes da primeira escrita.
		assertThat(messagingService.listConversations(a.userId(), null, null, 100))
			.noneMatch(conversation -> "GROUP".equals(conversation.kind())
				&& a.userId().equals(conversation.createdBy()));
	}
}
package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * A prévia do chat é a ÚLTIMA mensagem da conversa. `messages.created_at` guarda segundos, então
 * duas mensagens enviadas no mesmo segundo empatavam e a prévia podia continuar na anterior.
 *
 * O relógio é fixo de propósito: sem o conserto as duas mensagens nascem com o MESMO instante, que
 * é exatamente a condição que a produção truncava para o mesmo segundo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MessagePreviewOrderTests.FixedClock.class)
class MessagePreviewOrderTests {

	@TestConfiguration
	static class FixedClock {
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void previewShowsTheLastOfTwoMessagesSentInTheSameSecond() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("preview_order_a");
		var b = auth.authenticate("preview_order_b");

		MvcResult conversation = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "preview-order-direct")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		String id = objectMapper.readTree(conversation.getResponse().getContentAsString()).get("id").asText();

		send(a.token(), id, "primeira", "preview-order-1");
		send(a.token(), id, "segunda", "preview-order-2");

		MvcResult list = mockMvc.perform(get("/conversations/" + id + "/messages")
				.header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andReturn();
		var items = objectMapper.readTree(list.getResponse().getContentAsString()).get("items");
		assertThat(items.size()).isEqualTo(2);

		// O mais recente vem primeiro e é a segunda mensagem, num instante estritamente maior.
		var newest = items.get(0);
		var oldest = items.get(1);
		assertThat(newest.get("text").asText()).isEqualTo("segunda");
		assertThat(Instant.parse(newest.get("time").asText()))
			.isAfter(Instant.parse(oldest.get("time").asText()));

		MvcResult conversations = mockMvc.perform(get("/conversations").header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andReturn();
		var listItems = objectMapper.readTree(conversations.getResponse().getContentAsString()).get("items");
		String preview = null;
		String previewFrom = null;
		for (var item : listItems) {
			if (id.equals(item.get("id").asText())) {
				preview = item.get("lastMessage").asText();
				previewFrom = item.get("lastMessageFrom").asText();
			}
		}
		assertThat(preview).isEqualTo("segunda");
		// The inbox prefixes the viewer's own last message, so it needs to know who sent it.
		assertThat(previewFrom).isEqualTo(a.userId());
	}

	private void send(String token, String conversationId, String text, String key) throws Exception {
		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", text))))
			.andExpect(status().is2xxSuccessful());
	}
}

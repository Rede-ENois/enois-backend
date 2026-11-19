package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
 * A leitura é monotônica mesmo com a fila de mensagens à frente do relógio: o desempate de mesmo
 * segundo avança o created_at 1s por mensagem enviada rápido demais, e um lastReadAt gravado com o
 * relógio ficava ATRÁS das mensagens que a pessoa acabou de ver — o badge não zerava nunca
 * (enquanto o relógio não alcançasse). O read tem que avançar até a última mensagem vista.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadMonotonicTests {

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
	void readZeraONaoLidoMesmoComMensagensAFrenteDoRelogio() throws Exception {
		var a = auth.authenticate("msg_mono_read_a");
		var b = auth.authenticate("msg_mono_read_b");
		String conversationId = conversation(a, b.userId());

		// Rajada: cada envio no mesmo segundo empurra o created_at 1s à frente do relógio.
		List<String> chaves = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			chaves.add("msg-mono-" + System.nanoTime() + "-" + i);
			send(a, conversationId, "rajada " + i, chaves.get(i));
		}

		assertThat(unreadOf(b, conversationId)).as("a rajada conta como nao lida").isGreaterThan(0);

		mockMvc.perform(post("/conversations/" + conversationId + "/read")
				.header("X-Session-Token", b.token())
				.header("Idempotency-Key", "msg-mono-read-" + System.nanoTime()))
			.andExpect(status().is2xxSuccessful());

		assertThat(unreadOf(b, conversationId))
			.as("marcar como lida nao pode ficar atras das mensagens avancadas pelo desempate")
			.isZero();
	}

	private String conversation(TestAuthHelper.AuthSession a, String otherId) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "msg-mono-conv-" + a.userId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("kind", "DIRECT", "participantIds", List.of(otherId)))))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private String send(TestAuthHelper.AuthSession session, String conversationId, String text, String chave)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", session.token())
				.header("Idempotency-Key", chave)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", text))))
			.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
	}

	private int unreadOf(TestAuthHelper.AuthSession session, String conversationId) throws Exception {
		MvcResult result = mockMvc.perform(get("/conversations").param("limit", "50")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk()).andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
		for (JsonNode item : items) {
			if (conversationId.equals(item.path("id").asString())) {
				return item.path("unread").asInt();
			}
		}
		return -1;
	}
}

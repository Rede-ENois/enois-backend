package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
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
 * A chave de idempotencia protege contra o reenvio do MESMO comando. O hash precisa cobrir o
 * payload inteiro: sem os campos de cifragem, dois envios DIFERENTES com a mesma chave viravam
 * replay do primeiro — e a segunda mensagem (com a cifra dela) nunca chegava a ser gravada.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageSendIdempotencyTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void sameKeyWithDifferentCiphertextIsRejectedAsConflict() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("idem_e2e_a");
		var b = auth.authenticate("idem_e2e_b");
		String conversationId = conversation(a, b.userId(), "idem-e2e-direct");

		MvcResult first = sendE2e(a.token(), conversationId, "cifra-um", "aXYxMjM=", "idem-e2e-1");
		assertThat(first.getResponse().getStatus()).isEqualTo(201);

		// Mesma chave, outra cifra: nao e o mesmo comando, entao e conflito — nunca replay.
		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "idem-e2e-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(e2ePayload("cifra-dois", "aXYxMjM="))))
			.andExpect(status().isConflict());

		// A conversa tem UMA mensagem: a segunda nunca foi gravada como duplicata silenciosa.
		assertThat(messageCount(a.token(), conversationId)).isEqualTo(1);
	}

	@Test
	void sameKeyWithSameCiphertextIsAReplay() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("idem_e2e_c");
		var b = auth.authenticate("idem_e2e_d");
		String conversationId = conversation(a, b.userId(), "idem-e2e-direct-2");

		MvcResult first = sendE2e(a.token(), conversationId, "cifra-igual", "aXYxMjM=", "idem-e2e-2");
		MvcResult second = sendE2e(a.token(), conversationId, "cifra-igual", "aXYxMjM=", "idem-e2e-2");

		assertThat(second.getResponse().getStatus()).isEqualTo(201);
		assertThat(objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText())
			.isEqualTo(objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText());
		assertThat(messageCount(a.token(), conversationId)).isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentReplyIsRejectedAsConflict() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("idem_reply_a");
		var b = auth.authenticate("idem_reply_b");
		String conversationId = conversation(a, b.userId(), "idem-reply-direct");

		MvcResult quoted = send(a.token(), conversationId, "primeira", "idem-reply-msg-1");
		String firstId = objectMapper.readTree(quoted.getResponse().getContentAsString()).get("id").asText();
		MvcResult other = send(a.token(), conversationId, "segunda", "idem-reply-msg-2");
		String secondId = objectMapper.readTree(other.getResponse().getContentAsString()).get("id").asText();

		MvcResult first = send(a.token(), conversationId, "resposta", "idem-reply-2", firstId);
		assertThat(first.getResponse().getStatus()).isEqualTo(201);

		// Mesma chave e mesmo texto, mas outra mensagem citada: nao e o mesmo comando.
		mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "idem-reply-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("text", "resposta", "replyToId", secondId))))
			.andExpect(status().isConflict());
	}

	private Map<String, Object> e2ePayload(String ciphertext, String iv) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("text", ciphertext);
		payload.put("e2e", true);
		payload.put("iv", iv);
		payload.put("encryptedKeys", "{\"other\":\"chave\"}");
		return payload;
	}

	private String conversation(TestAuthHelper.AuthSession a, String otherId, String key) throws Exception {
		MvcResult created = mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", otherId))))
			.andExpect(status().is2xxSuccessful())
			.andReturn();
		return objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
	}

	private MvcResult sendE2e(String token, String conversationId, String ciphertext, String iv, String key)
			throws Exception {
		return mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(e2ePayload(ciphertext, iv))))
			.andExpect(status().isCreated())
			.andReturn();
	}

	private MvcResult send(String token, String conversationId, String text, String key) throws Exception {
		return send(token, conversationId, text, key, null);
	}

	private MvcResult send(String token, String conversationId, String text, String key, String replyToId)
			throws Exception {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("text", text);
		if (replyToId != null) {
			payload.put("replyToId", replyToId);
		}
		return mockMvc.perform(post("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", token)
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload)))
			.andExpect(status().isCreated())
			.andReturn();
	}

	private int messageCount(String token, String conversationId) throws Exception {
		MvcResult list = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/conversations/" + conversationId + "/messages")
				.header("X-Session-Token", token))
			.andExpect(status().isOk())
			.andReturn();
		return objectMapper.readTree(list.getResponse().getContentAsString()).get("items").size();
	}
}

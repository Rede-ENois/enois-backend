package com.socialcoisa.rede.messaging.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * A paginacao da conversa nao pode PULAR mensagem.
 *
 * <p>`messages.created_at` guarda segundos: duas mensagens enviadas no mesmo segundo empacam, e o
 * cursor so com o instante pulava a mensagem que empatou com a ultima da pagina. O cursor passou a
 * carregar o par (instante, id) e a consulta a orderar pelos dois.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MessagePaginationSameSecondTests.FixedClock.class)
class MessagePaginationSameSecondTests {

	@TestConfiguration
	static class FixedClock {
		@Bean
		@Primary
		java.time.Clock fixedClock() {
			return java.time.Clock.fixed(java.time.Instant.parse("2026-11-13T12:00:00Z"),
				java.time.ZoneOffset.UTC);
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void paginaSeguinteNaoPulaMensagemDoMesmoSegundo() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("mpss_a");
		var b = auth.authenticate("mpss_b");
		String conversationId = conversation(a, b.userId(), "mpss-direct");

		// Todas as mensagens nascem no mesmo instante (relogio fixo): o empate e o caso real.
		for (int i = 1; i <= 4; i++) {
			mockMvc.perform(post("/conversations/" + conversationId + "/messages")
					.header("X-Session-Token", a.token())
					.header("Idempotency-Key", "mpss-msg-" + i)
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("text", "mensagem " + i))))
				.andExpect(status().isCreated());
		}

		MvcResult first = mockMvc.perform(get("/conversations/" + conversationId + "/messages?limit=2")
				.header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andReturn();
		var firstPage = objectMapper.readTree(first.getResponse().getContentAsString());
		assertThat(firstPage.get("items").size()).isEqualTo(2);
		String cursor = firstPage.get("nextCursor").asText();

		MvcResult second = mockMvc.perform(get("/conversations/" + conversationId + "/messages?limit=2&cursor="
				+ java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8))
				.header("X-Session-Token", a.token()))
			.andExpect(status().isOk())
			.andReturn();
		var secondPage = objectMapper.readTree(second.getResponse().getContentAsString());

		// As duas paginas, juntas, tem as QUATRO mensagens: nenhuma foi pulada no desempate.
		assertThat(firstPage.get("items").size() + secondPage.get("items").size()).isEqualTo(4);
		var ids = new java.util.LinkedHashSet<String>();
		for (var page : List.of(firstPage, secondPage)) {
			for (var item : page.get("items")) {
				ids.add(item.get("id").asText());
			}
		}
		assertThat(ids).hasSize(4);
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
}

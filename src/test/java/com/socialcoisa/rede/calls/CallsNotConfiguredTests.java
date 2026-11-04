package com.socialcoisa.rede.calls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Ambiente sem servidor de reunião configurado: a chamada não começa e nenhuma linha é criada. Um
 * botão que falha com erro explícito é melhor do que um painel apontando para uma instância aberta.
 */
@SpringBootTest(properties = {
		"rede.calls.jitsi.url=",
		"rede.calls.jitsi.jwt-secret=",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class CallsNotConfiguredTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void semEnderecoChamadaNaoComecaENadaEAberto() throws Exception {
		TestAuthHelper auth = new TestAuthHelper(mockMvc, objectMapper);
		var a = auth.authenticate("call-cfg-a");
		var b = auth.authenticate("call-cfg-b");

		String conversation = objectMapper.readTree(mockMvc.perform(post("/conversations/direct")
				.header("X-Session-Token", a.token())
				.header("Idempotency-Key", "dm-call-cfg")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("userId", b.userId()))))
			.andReturn().getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/conversations/" + conversation + "/calls").header("X-Session-Token", a.token()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("CALLS_NOT_CONFIGURED"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM call_sessions WHERE conversation_id = ?",
			Integer.class, conversation)).isZero();
	}
}

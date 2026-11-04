package com.socialcoisa.rede.calls;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Sonda manual da mídia: sobe o app de verdade apontado para uma stack local do
 * {@code deploy/jitsi}, abre uma chamada 1:1 pelos mesmos endpoints do app e grava os tokens de
 * entrada em arquivo para dois navegadores entrarem na mesma sala.
 *
 * <p>Não roda na suíte: só executa quando {@code -Dcall.probe.url} é passado, porque depende de
 * infraestrutura externa e do navegador. O roteiro está em {@code docs/CHAMADAS.md}.
 */
@EnabledIfSystemProperty(named = "call.probe.url", matches = ".+")
@SpringBootTest(properties = {
		"rede.calls.jitsi.url=${call.probe.url}",
		"rede.calls.jitsi.jwt-secret=${call.probe.secret}",
		"rede.calls.jitsi.jwt-issuer=rede",
		"spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
public class CallMediaProbe {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void gravaTokensDosDoisLadosDaChamada() throws Exception {
		var auth = new TestAuthHelper(mockMvc, objectMapper);
		var alex = auth.authenticate("probe-alex");
		var bruno = auth.authenticate("probe-bruno");

		String conversation = objectMapper.readTree(mockMvc.perform(
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/conversations/direct")
					.header("X-Session-Token", alex.token())
					.header("Idempotency-Key", "probe-dm")
					.contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(java.util.Map.of("userId", bruno.userId()))))
			.andReturn().getResponse().getContentAsString()).get("id").asText();

		String callId = objectMapper.readTree(mockMvc.perform(
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders
					.post("/conversations/" + conversation + "/calls")
					.header("X-Session-Token", alex.token())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"kind\":\"VIDEO\"}"))
			.andReturn().getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/calls/" + callId + "/answer").header("X-Session-Token", bruno.token()));

		String joinA = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/calls/" + callId + "/join").header("X-Session-Token", alex.token()))
			.andReturn().getResponse().getContentAsString();
		String joinB = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/calls/" + callId + "/join").header("X-Session-Token", bruno.token()))
			.andReturn().getResponse().getContentAsString();

		Path out = Path.of(System.getProperty("call.probe.out", "/tmp/call-probe"));
		Files.createDirectories(out);
		Files.writeString(out.resolve("alex.json"), joinA);
		Files.writeString(out.resolve("bruno.json"), joinB);
	}
}

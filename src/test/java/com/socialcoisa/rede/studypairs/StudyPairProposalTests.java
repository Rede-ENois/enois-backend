package com.socialcoisa.rede.studypairs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.socialcoisa.rede.shared.web.TestAuthHelper;
import com.socialcoisa.rede.shared.web.TestAuthHelper.AuthSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A proposta do par (issue #619): o casamento nasce PENDING, o aceite mútuo com quebra-gelo abre o
 * par e a recusa encerra a proposta devolvendo o pedido à fila. Prova do comportamento novo: antes
 * o casamento já era ACTIVE na hora, sem proposta — não havia o que aceitar ou recusar.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudyPairProposalTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	@Test
	void matchStaysPendingUntilBothAccept() throws Exception {
		AuthSession a = auth.authenticate("sp-prop-a");
		AuthSession b = auth.authenticate("sp-prop-b");

		requestPair(a, "goal sp-prop").andExpect(status().isCreated());
		requestPair(b, "goal sp-prop").andExpect(status().isCreated());

		String pairId = proposalId(a);
		assertThat(statusOf(pairId)).isEqualTo("PENDING");

		// A proposta aparece em /me com o par revelado dos dois lados (revelação simétrica).
		JsonNode mineA = body(me(a));
		assertThat(mineA.get("pairs").size()).isZero();
		assertThat(mineA.get("proposals").size()).isEqualTo(1);
		assertThat(mineA.get("proposals").get(0).get("partner").get("id").asText()).isEqualTo(b.userId());
		JsonNode mineB = body(me(b));
		assertThat(mineB.get("proposals").get(0).get("partner").get("id").asText()).isEqualTo(a.userId());

		// Aceite de um lado não abre o par.
		accept(a, pairId).andExpect(status().isOk()).andExpect(jsonPath("$.pairActive").value(false));
		assertThat(statusOf(pairId)).isEqualTo("PENDING");

		// O aceite do outro abre o par.
		accept(b, pairId).andExpect(status().isOk()).andExpect(jsonPath("$.pairActive").value(true));
		assertThat(statusOf(pairId)).isEqualTo("ACTIVE");

		// O par ativo aparece em /me e o quebra-gelo do outro lado chegou junto.
		JsonNode ativo = body(me(a));
		assertThat(ativo.get("pairs").size()).isEqualTo(1);
	}

	@Test
	void declinedProposalEndsThePairAndFreesTheRequest() throws Exception {
		AuthSession a = auth.authenticate("sp-dec-a");
		AuthSession b = auth.authenticate("sp-dec-b");

		requestPair(a, "goal sp-dec").andExpect(status().isCreated());
		requestPair(b, "goal sp-dec").andExpect(status().isCreated());
		String pairId = proposalId(a);

		decline(a, pairId).andExpect(status().isOk());

		// A proposta acabou e o pedido de quem não recusou voltou para a fila.
		assertThat(statusOf(pairId)).isEqualTo("ENDED");
		assertThat(body(me(b)).get("openRequest")).isNotNull();

		// O mesmo pedido pode casar de novo com um terceiro.
		AuthSession c = auth.authenticate("sp-dec-c");
		requestPair(c, "goal sp-dec").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("MATCHED"));
	}

	@Test
	void icebreakerAnswerIsRequiredToAccept() throws Exception {
		AuthSession a = auth.authenticate("sp-ice-a");
		AuthSession b = auth.authenticate("sp-ice-b");
		requestPair(a, "goal sp-ice").andExpect(status().isCreated());
		requestPair(b, "goal sp-ice").andExpect(status().isCreated());
		String pairId = proposalId(a);

		// Sem resposta na pergunta obrigatória, o aceite é recusado.
		mockMvc.perform(post("/study-pairs/" + pairId + "/accept").header("X-Session-Token", a.token())
				.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("answers", Map.of()))))
			.andExpect(status().isBadRequest());

		accept(a, pairId).andExpect(status().isOk());
	}

	private String proposalId(AuthSession session) throws Exception {
		return body(me(session)).get("proposals").get(0).get("id").asText();
	}

	private String statusOf(String pairId) {
		return jdbc.queryForObject("select status from study_pairs where id = ?", String.class, pairId);
	}

	private ResultActions accept(AuthSession session, String pairId) throws Exception {
		return mockMvc.perform(post("/study-pairs/" + pairId + "/accept").header("X-Session-Token", session.token())
			.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("answers",
				Map.of("ice_why_now", "Quero aprender de verdade")))));
	}

	private ResultActions decline(AuthSession session, String pairId) throws Exception {
		return mockMvc.perform(post("/study-pairs/" + pairId + "/decline").header("X-Session-Token", session.token())
			.header("Idempotency-Key", key()));
	}

	private ResultActions requestPair(AuthSession session, String goal) throws Exception {
		Map<String, Object> payload = new HashMap<>();
		payload.put("goal", goal);
		return mockMvc.perform(post("/study-pairs/requests").header("X-Session-Token", session.token())
			.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(payload)));
	}

	private org.springframework.test.web.servlet.MvcResult me(AuthSession session) throws Exception {
		return mockMvc.perform(get("/study-pairs/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk()).andReturn();
	}

	private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static String key() {
		return "sp-" + UUID.randomUUID();
	}
}

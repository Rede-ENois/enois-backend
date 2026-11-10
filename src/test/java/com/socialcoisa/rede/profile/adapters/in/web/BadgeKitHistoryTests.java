package com.socialcoisa.rede.profile.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Histórico de kits de emblemas: o conjunto ANTERIOR entra no histórico quando a lista muda de
 * verdade; o histórico guarda no máximo 3; aplicar um kit do histórico troca a lista (e empurra
 * o conjunto substituído para o histórico).KIT nunca guarda arquivo — só a referência.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BadgeKitHistoryTests {

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

	private void setBadges(TestAuthHelper.AuthSession session, List<String> badges) throws Exception {
		String json = objectMapper.writeValueAsString(java.util.Map.of("badges", badges));
		mockMvc.perform(put("/me/badges").header("X-User-Id", session.userId())
			.header("X-Session-Token", session.token()).contentType(MediaType.APPLICATION_JSON)
			.content(json)).andExpect(status().isOk());
	}

	private List<tools.jackson.databind.JsonNode> kitsOf(TestAuthHelper.AuthSession session) throws Exception {
		String body = mockMvc.perform(get("/me/badge-kits").header("X-User-Id", session.userId())
			.header("X-Session-Token", session.token())).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		var list = objectMapper.readTree(body);
		List<tools.jackson.databind.JsonNode> kits = new java.util.ArrayList<>();
		list.forEach(kits::add);
		return kits;
	}

	private String kitIdOf(TestAuthHelper.AuthSession session, int index) throws Exception {
		return kitsOf(session).get(index).get("id").asText();
	}

	@Test
	void changingBadgesRecordsPreviousSetAsKit() throws Exception {
		var session = auth.authenticate("user_bkit_a");

		// A primeira troca parte da lista vazia: não gera kit vazio (nada a restaurar).
		setBadges(session, List.of("https://cdn.enois.ia.br/k1-a.png", "https://cdn.enois.ia.br/k2-a.png"));
		org.junit.jupiter.api.Assertions.assertTrue(kitsOf(session).isEmpty(), "lista vazia não é kit");

		setBadges(session, List.of("https://cdn.enois.ia.br/k3-a.png"));
		var kits = kitsOf(session);
		org.junit.jupiter.api.Assertions.assertEquals(1, kits.size());
		org.junit.jupiter.api.Assertions.assertEquals(
			List.of("https://cdn.enois.ia.br/k1-a.png", "https://cdn.enois.ia.br/k2-a.png"),
			objectMapper.convertValue(kits.get(0).get("badges"), List.class));
	}

	@Test
	void noChangeProducesNoSnapshot() throws Exception {
		var session = auth.authenticate("user_bkit_b");
		setBadges(session, List.of("https://cdn.enois.ia.br/b1-b.png"));
		setBadges(session, List.of("https://cdn.enois.ia.br/b1-b.png")); // mesma lista: nada muda
		org.junit.jupiter.api.Assertions.assertTrue(kitsOf(session).isEmpty());
	}

	@Test
	void historyKeepsOnlyTheThreeMostRecent() throws Exception {
		var session = auth.authenticate("user_bkit_c");
		setBadges(session, List.of("https://cdn.enois.ia.br/c1.png"));
		setBadges(session, List.of("https://cdn.enois.ia.br/c2.png"));
		setBadges(session, List.of("https://cdn.enois.ia.br/c3.png"));
		setBadges(session, List.of("https://cdn.enois.ia.br/c4.png"));
		setBadges(session, List.of("https://cdn.enois.ia.br/c5.png"));

		var kits = kitsOf(session);
		org.junit.jupiter.api.Assertions.assertEquals(3, kits.size(), "histórico não passa de 3");
		// Mais recente primeiro: o conjunto substituído pela última troca vem primeiro.
		org.junit.jupiter.api.Assertions.assertEquals(List.of("https://cdn.enois.ia.br/c4.png"),
			objectMapper.convertValue(kits.get(0).get("badges"), List.class));
		org.junit.jupiter.api.Assertions.assertEquals(List.of("https://cdn.enois.ia.br/c3.png"),
			objectMapper.convertValue(kits.get(1).get("badges"), List.class));
	}

	@Test
	void applyingKitRestoresTheSetAndPushesTheReplacedOne() throws Exception {
		var session = auth.authenticate("user_bkit_d");
		setBadges(session, List.of("https://cdn.enois.ia.br/d1.png", "https://cdn.enois.ia.br/d2.png"));
		setBadges(session, List.of("https://cdn.enois.ia.br/d3.png"));

		String kitId = kitIdOf(session, 0);
		mockMvc.perform(post("/me/badge-kits/{kitId}/apply", kitId).header("X-User-Id", session.userId())
			.header("X-Session-Token", session.token())).andExpect(status().isOk())
			.andExpect(jsonPath("$.badges.length()").value(2))
			.andExpect(jsonPath("$.badges[0]").value("https://cdn.enois.ia.br/d1.png"));

		// O kit aplicado saiu do histórico (virou o conjunto atual); o d3 entrou no lugar.
		var kits = kitsOf(session);
		org.junit.jupiter.api.Assertions.assertEquals(List.of("https://cdn.enois.ia.br/d3.png"),
			objectMapper.convertValue(kits.get(0).get("badges"), List.class));
		org.junit.jupiter.api.Assertions.assertTrue(kits.stream().noneMatch(kit -> kit.get("id").asText().equals(kitId)),
			"kit aplicado não pode continuar no histórico senão reaparece sem sentido");
	}

	@Test
	void kitOfAnotherPersonIsNotFound() throws Exception {
		var owner = auth.authenticate("user_bkit_e");
		setBadges(owner, List.of("https://cdn.enois.ia.br/e1.png"));
		setBadges(owner, List.of("https://cdn.enois.ia.br/e2.png"));
		String kitId = kitIdOf(owner, 0);

		var stranger = auth.authenticate("user_bkit_f");
		mockMvc.perform(post("/me/badge-kits/{kitId}/apply", kitId).header("X-User-Id", stranger.userId())
			.header("X-Session-Token", stranger.token())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("KIT_NOT_FOUND"));
	}

	@Test
	void clearingBadgesAlsoSnapshots() throws Exception {
		var session = auth.authenticate("user_bkit_g");
		setBadges(session, List.of("https://cdn.enois.ia.br/g1.png"));
		setBadges(session, List.of());
		var kits = kitsOf(session);
		org.junit.jupiter.api.Assertions.assertEquals(1, kits.size());
		org.junit.jupiter.api.Assertions.assertEquals(List.of("https://cdn.enois.ia.br/g1.png"),
			objectMapper.convertValue(kits.get(0).get("badges"), List.class));
	}

	@Test
	void pruningDeletesTheRowsOfOldKits() throws Exception {
		var session = auth.authenticate("user_bkit_h");
		for (int i = 0; i < 5; i++) {
			setBadges(session, List.of("https://cdn.enois.ia.br/h" + i + ".png"));
		}
		Integer rows = jdbc.queryForObject("select count(*) from badge_kits where user_id = ?", Integer.class,
			session.userId());
		org.junit.jupiter.api.Assertions.assertEquals(3, rows, "poda de verdade: linha some, não fica invisível");
	}
}

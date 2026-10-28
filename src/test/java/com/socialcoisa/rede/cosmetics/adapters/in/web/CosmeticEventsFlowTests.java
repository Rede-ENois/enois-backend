package com.socialcoisa.rede.cosmetics.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.socialcoisa.rede.cosmetics.application.CosmeticsService;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The free events of 2026-10-31: the Saci x Halloween duel and the Saci hunt. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SeasonalTestClock.class)
class CosmeticEventsFlowTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private SeasonalTestClock.MovableClock clock;

	@Autowired
	private CosmeticsService cosmetics;

	private TestAuthHelper auth;

	@BeforeEach
	void setUp() {
		clock.set(SeasonalTestClock.DUEL_DAY);
		auth = new TestAuthHelper(mockMvc, objectMapper);
	}

	private MvcResult choose(TestAuthHelper.AuthSession user, String choice) throws Exception {
		return mockMvc.perform(post("/eventos/saci-halloween/escolha").header("X-Session-Token", user.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("choice", choice))))
			.andReturn();
	}

	private JsonNode body(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	@Test
	void duelTakesOneChoicePerPersonAndGrantsTheTeamBadge() throws Exception {
		mockMvc.perform(get("/eventos/saci-halloween"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.startsAt").value("2026-10-31T03:00:00Z"))
			.andExpect(jsonPath("$.endsAt").value("2026-11-01T02:59:59Z"))
			.andExpect(jsonPath("$.counts.saci").isNumber())
			.andExpect(jsonPath("$.counts.halloween").isNumber())
			.andExpect(jsonPath("$.myChoice").doesNotExist())
			.andExpect(jsonPath("$.winner").doesNotExist());

		var fan = auth.authenticate("duel_saci_fan");
		long before = body(mockMvc.perform(get("/eventos/saci-halloween")).andReturn()).get("counts").get("saci").asLong();
		MvcResult chosen = choose(fan, "saci");
		assertThat(chosen.getResponse().getStatus()).isEqualTo(200);
		assertThat(body(chosen).get("myChoice").asText()).isEqualTo("saci");
		assertThat(body(chosen).get("counts").get("saci").asLong()).isEqualTo(before + 1);
		assertThat(cosmetics.owns(fan.userId(), "emblema-time-saci")).isTrue();
		assertThat(cosmetics.owns(fan.userId(), "emblema-time-halloween")).isFalse();

		MvcResult again = choose(fan, "halloween");
		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(body(again).get("code").asText()).isEqualTo("ALREADY_CHOSEN");
		mockMvc.perform(get("/eventos/saci-halloween").header("X-Session-Token", fan.token()))
			.andExpect(jsonPath("$.myChoice").value("saci"));

		MvcResult invalid = choose(auth.authenticate("duel_invalid"), "curupira");
		assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
		assertThat(body(invalid).get("code").asText()).isEqualTo("INVALID_CHOICE");

		// The team badge is owned like any badge: it shows in the shop and can be equipped.
		mockMvc.perform(post("/loja/emblema-time-saci/equipar").header("X-Session-Token", fan.token()))
			.andExpect(status().isOk());
	}

	@Test
	void afterTheEndTheDuelIsClosedAndTheWinningTeamGetsTheVictoryBadge() throws Exception {
		var saci = auth.authenticate("duel_winner_saci");
		var saci2 = auth.authenticate("duel_winner_saci2");
		var halloween = auth.authenticate("duel_winner_halloween");
		assertThat(choose(saci, "saci").getResponse().getStatus()).isEqualTo(200);
		assertThat(choose(saci2, "saci").getResponse().getStatus()).isEqualTo(200);
		assertThat(choose(halloween, "halloween").getResponse().getStatus()).isEqualTo(200);

		clock.set(SeasonalTestClock.AFTER_EVENTS);
		MvcResult closed = choose(auth.authenticate("duel_late"), "halloween");
		assertThat(closed.getResponse().getStatus()).isEqualTo(409);
		assertThat(body(closed).get("code").asText()).isEqualTo("EVENT_CLOSED");

		JsonNode duel = body(mockMvc.perform(get("/eventos/saci-halloween")).andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false)).andReturn());
		long saciVotes = duel.get("counts").get("saci").asLong();
		long halloweenVotes = duel.get("counts").get("halloween").asLong();
		// The database is shared with other tests, so the winner is whatever the counts say.
		String expectedWinner = saciVotes == halloweenVotes ? null : saciVotes > halloweenVotes ? "saci" : "halloween";
		assertThat(duel.get("winner").isNull() ? null : duel.get("winner").asText()).isEqualTo(expectedWinner);
		assertThat(cosmetics.owns(saci.userId(), "emblema-vitoria-saci")).isEqualTo(saciVotes >= halloweenVotes);
		assertThat(cosmetics.owns(halloween.userId(), "emblema-vitoria-halloween"))
			.isEqualTo(halloweenVotes >= saciVotes);
		assertThat(cosmetics.owns(saci.userId(), "emblema-vitoria-halloween")).isFalse();

		// Reading again is idempotent.
		mockMvc.perform(get("/eventos/saci-halloween")).andExpect(status().isOk());
		assertThat(cosmetics.owns(saci2.userId(), "emblema-vitoria-saci")).isEqualTo(saciVotes >= halloweenVotes);
	}

	@Test
	void huntCountsSpotsOnceAndTheSeventhGrantsTheBadge() throws Exception {
		var hunter = auth.authenticate("hunt_hunter");
		mockMvc.perform(get("/eventos/caca-saci").header("X-Session-Token", hunter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true))
			.andExpect(jsonPath("$.spots").value(7))
			.andExpect(jsonPath("$.found").isEmpty())
			.andExpect(jsonPath("$.completed").value(false));

		List<String> spots = List.of("feed", "vagas", "comunidades", "blog", "carteira", "mensagens");
		for (String spot : spots) {
			mockMvc.perform(post("/eventos/caca-saci/achados/" + spot).header("X-Session-Token", hunter.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));
		}
		mockMvc.perform(post("/eventos/caca-saci/achados/feed").header("X-Session-Token", hunter.token()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.found.length()").value(6));
		mockMvc.perform(post("/eventos/caca-saci/achados/porao").header("X-Session-Token", hunter.token()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SPOT_NOT_FOUND"));
		assertThat(cosmetics.owns(hunter.userId(), "emblema-achei-saci")).isFalse();

		mockMvc.perform(post("/eventos/caca-saci/achados/perfil").header("X-Session-Token", hunter.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.found.length()").value(7))
			.andExpect(jsonPath("$.found[6]").value("perfil"))
			.andExpect(jsonPath("$.completed").value(true));
		assertThat(cosmetics.owns(hunter.userId(), "emblema-achei-saci")).isTrue();
		mockMvc.perform(post("/loja/emblema-achei-saci/comprar").header("X-Session-Token", hunter.token()))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_FOR_SALE"));

		mockMvc.perform(get("/eventos/caca-saci"))
			.andExpect(jsonPath("$.found").isEmpty());

		clock.set(SeasonalTestClock.AFTER_EVENTS);
		mockMvc.perform(post("/eventos/caca-saci/achados/feed").header("X-Session-Token", hunter.token()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_CLOSED"));
	}
}

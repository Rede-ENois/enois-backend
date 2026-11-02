package com.socialcoisa.rede.events.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.socialcoisa.rede.events.application.EventRecurrenceService;
import com.socialcoisa.rede.events.application.port.out.EventRepository;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Encontros por cidade: filtro sem acento, so presenciais/hibridos, e online nao aceita cidade. */
@SpringBootTest
@AutoConfigureMockMvc
class EventCityFilterTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private EventRecurrenceService recurrence;

	@Autowired
	private EventRepository events;

	private ResultActions create(TestAuthHelper.AuthSession who, String title, String format, String city,
			String state, String key) throws Exception {
		return create(who, title, format, city, state, LocalDate.now().plusMonths(2), null, key);
	}

	private ResultActions create(TestAuthHelper.AuthSession who, String title, String format, String city,
			String state, LocalDate date, String recurrence, String key) throws Exception {
		Map<String, Object> body = new HashMap<>(Map.of("title", title, "date", date.toString(), "time", "19:00",
			"format", format, "host", "Enois"));
		if (recurrence != null) {
			body.put("recurrence", recurrence);
		}
		if (city != null) {
			body.put("city", city);
			body.put("state", state);
		}
		return mockMvc.perform(post("/events")
			.header("X-Session-Token", who.token())
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String id(ResultActions created) throws Exception {
		return objectMapper.readTree(created.andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString()).get("id").asText();
	}

	@Test
	void filtersInPersonEventsByCityIgnoringAccents() throws Exception {
		var host = new TestAuthHelper(mockMvc, objectMapper).authenticate("ecity-1");
		String meetup = id(create(host, "ecity Meetup Floripa", "IN_PERSON", "Florianópolis", "SC", "ecity-1a"));
		String hybrid = id(create(host, "ecity Hibrido Floripa", "HYBRID", "florianopolis", "sc", "ecity-1b"));
		String joinville = id(create(host, "ecity Joinville", "IN_PERSON", "Joinville", "SC", "ecity-1c"));
		String online = id(create(host, "ecity Online", "ONLINE", null, null, "ecity-1d"));
		List<String> mine = List.of(meetup, hybrid, joinville, online);

		String json = mockMvc.perform(get("/events").param("city", "FLORIANOPOLIS").param("state", "SC")
				.param("limit", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].city").exists())
			.andReturn().getResponse().getContentAsString();
		List<String> found = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(json).get("items")) {
			if (mine.contains(item.get("id").asText())) {
				found.add(item.get("id").asText());
			}
		}
		assertThat(found).containsExactlyInAnyOrder(meetup, hybrid);
	}

	@Test
	void refusesCityOnAnOnlineEventAndAnInvalidState() throws Exception {
		var host = new TestAuthHelper(mockMvc, objectMapper).authenticate("ecity-2");
		create(host, "ecity Online com cidade", "ONLINE", "Curitiba", "PR", "ecity-2a")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("EVENT_CITY_NOT_IN_PERSON"));
		create(host, "ecity UF ruim", "IN_PERSON", "Curitiba", "ZZ", "ecity-2b")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_STATE"));
		mockMvc.perform(get("/events").param("state", "ZZ"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_STATE"));
	}

	@Test
	void nextOccurrenceOfARecurringMeetupKeepsTheCityAndMatchesBothFilters() throws Exception {
		var host = new TestAuthHelper(mockMvc, objectMapper).authenticate("ecity-3");
		LocalDate yesterday = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1);
		String first = id(create(host, "ecity Ritual Goiania", "IN_PERSON", "Goiânia", "GO", yesterday, "WEEKLY",
			"ecity-3a"));
		String single = id(create(host, "ecity Avulso Goiania", "IN_PERSON", "Goiania", "GO",
			LocalDate.now().plusMonths(1), null, "ecity-3b"));

		recurrence.spawnNextOccurrences();

		Event next = events.findLatestInSeries(first).orElseThrow();
		assertThat(next.id()).isNotEqualTo(first);
		assertThat(next.place()).isNotNull();
		assertThat(next.place().city()).isEqualTo("Goiânia");
		assertThat(next.place().state()).isEqualTo("GO");

		String json = mockMvc.perform(get("/events").param("city", "goiania").param("state", "GO")
				.param("recurring", "true").param("limit", "100"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> found = new ArrayList<>();
		for (JsonNode item : objectMapper.readTree(json).get("items")) {
			found.add(item.get("id").asText());
		}
		assertThat(found).contains(next.id()).doesNotContain(single);
	}
}

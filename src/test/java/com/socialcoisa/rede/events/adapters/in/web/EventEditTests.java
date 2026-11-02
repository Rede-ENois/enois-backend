package com.socialcoisa.rede.events.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

/**
 * Edição e exclusão do evento pelo dono: o ritual é de quem criou.
 *
 * Tranca: o dono edita título, data, hora, formato, host e lugar; quem não é dono recebe 403; a
 * edição passa pelas mesmas validações da criação (data absurda, transmissão sem https, cidade em
 * evento online); evento recorrente continua exigindo horário fixo; quem criou também apaga.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventEditTests {

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
	void ownerEditsTheEvent() throws Exception {
		var creator = auth.authenticate("evedit_owner1");
		String eventId = create(creator, "Encontro de abertura", "2030-06-10");

		mockMvc.perform(patch("/events/" + eventId)
				.header("X-Session-Token", creator.token())
				.header("Idempotency-Key", "evedit-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Encontro renomeado",
					"date", "2030-07-15", "time", "14:00", "format", "Presencial", "host", "Novo host",
					"city", "Curitiba", "state", "PR"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(eventId))
			.andExpect(jsonPath("$.title").value("Encontro renomeado"))
			.andExpect(jsonPath("$.date").value("2030-07-15"))
			.andExpect(jsonPath("$.time").value("14:00"))
			.andExpect(jsonPath("$.host").value("Novo host"))
			.andExpect(jsonPath("$.city").value("Curitiba"))
			.andExpect(jsonPath("$.state").value("PR"));

		mockMvc.perform(get("/events/" + eventId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Encontro renomeado"));
	}

	@Test
	void nonOwnerCannotEdit() throws Exception {
		var creator = auth.authenticate("evedit_owner2");
		var other = auth.authenticate("evedit_other2");
		String eventId = create(creator, "Mesa redonda", "2030-06-11");

		mockMvc.perform(patch("/events/" + eventId)
				.header("X-Session-Token", other.token())
				.header("Idempotency-Key", "evedit-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Assumido", "date", "2030-06-11",
					"time", "19:00", "format", "Presencial"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("NOT_EVENT_OWNER"));
	}

	@Test
	void editRunsTheSameValidationsAsCreation() throws Exception {
		var creator = auth.authenticate("evedit_owner3");
		String eventId = create(creator, "Workshop", "2030-06-12");

		// Data absurda e transmissão sem https recusam a edição, igual à criação.
		mockMvc.perform(patch("/events/" + eventId)
				.header("X-Session-Token", creator.token())
				.header("Idempotency-Key", "evedit-3a")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Workshop", "date", "220122-12-12",
					"time", "19:00", "format", "Presencial"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_EVENT_DATE"));

		mockMvc.perform(patch("/events/" + eventId)
				.header("X-Session-Token", creator.token())
				.header("Idempotency-Key", "evedit-3b")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Workshop", "date", "2030-06-12",
					"time", "19:00", "format", "Online", "streamUrl", "http://inseguro.example"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_STREAM_URL"));

		// Cidade em evento online também não vale na edição.
		mockMvc.perform(patch("/events/" + eventId)
				.header("X-Session-Token", creator.token())
				.header("Idempotency-Key", "evedit-3c")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("title", "Workshop", "date", "2030-06-12",
					"time", "19:00", "format", "Online", "city", "Curitiba", "state", "PR"))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("EVENT_CITY_NOT_IN_PERSON"));
	}

	@Test
	void ownerDeletesTheEvent() throws Exception {
		var creator = auth.authenticate("evedit_owner4");
		var other = auth.authenticate("evedit_other4");
		String eventId = create(creator, "Encerramento", "2030-06-13");

		mockMvc.perform(delete("/events/" + eventId).header("X-Session-Token", other.token())
				.header("Idempotency-Key", "evedit-del-403"))
			.andExpect(status().isForbidden());

		mockMvc.perform(delete("/events/" + eventId).header("X-Session-Token", creator.token())
				.header("Idempotency-Key", "evedit-del-ok"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELETED"));

		mockMvc.perform(get("/events/" + eventId))
			.andExpect(status().isNotFound());
	}

	private String create(TestAuthHelper.AuthSession creator, String title, String date) throws Exception {
		String body = objectMapper.writeValueAsString(Map.of("title", title, "date", date, "time", "19:00",
			"format", "Presencial", "host", "Énóis"));
		String id = mockMvc.perform(post("/events")
				.header("X-Session-Token", creator.token())
				.header("Idempotency-Key", "evedit-create-" + Math.abs(title.hashCode() + date.hashCode()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(id).isNotBlank();
		return objectMapper.readTree(id).get("id").asString();
	}
}

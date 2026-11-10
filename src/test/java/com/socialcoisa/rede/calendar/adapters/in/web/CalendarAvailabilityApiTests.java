package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Contrato HTTP da disponibilidade: sem sessão é 401, a semana sai ordenada e volta no mesmo
 * `hh:mm` que entrou, texto malformado é 400 com código, e a leitura é sempre de quem está lendo.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarAvailabilityApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;

	private TestAuthHelper.AuthSession account() throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate("ava_" + UUID.randomUUID());
	}

	private String body(String windows) {
		return "{\"windows\":" + windows + "}";
	}

	@Test
	void readingWithoutSessionIs401() throws Exception {
		mockMvc.perform(get("/calendar/availability")).andExpect(status().isUnauthorized());
	}

	@Test
	void writingWithoutSessionIs401() throws Exception {
		mockMvc.perform(put("/calendar/availability").contentType(MediaType.APPLICATION_JSON)
			.content(body("[{\"dayOfWeek\":1,\"startsAt\":\"09:00\",\"endsAt\":\"12:00\","
				+ "\"timezone\":\"America/Sao_Paulo\"}]")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void theReaderSavesAndReadsTheirOwnWeek() throws Exception {
		TestAuthHelper.AuthSession session = account();

		mockMvc.perform(put("/calendar/availability").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("[{\"dayOfWeek\":1,\"startsAt\":\"09:00\",\"endsAt\":\"12:00\","
					+ "\"timezone\":\"America/Sao_Paulo\"},"
					+ "{\"dayOfWeek\":3,\"startsAt\":\"14:00\",\"endsAt\":\"18:00\","
					+ "\"timezone\":\"America/Sao_Paulo\"}]")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].dayOfWeek").value(1))
			.andExpect(jsonPath("$[0].startsAt").value("09:00"))
			.andExpect(jsonPath("$[0].endsAt").value("12:00"))
			.andExpect(jsonPath("$[0].timezone").value("America/Sao_Paulo"))
			.andExpect(jsonPath("$[1].dayOfWeek").value(3));

		mockMvc.perform(get("/calendar/availability").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].id").isNotEmpty());
	}

	@Test
	void anEmptyBodyReplacesTheWeekWithNothing() throws Exception {
		TestAuthHelper.AuthSession session = account();

		mockMvc.perform(put("/calendar/availability").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON).content(body("[]")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void anUnknownZoneIs400WithCode() throws Exception {
		TestAuthHelper.AuthSession session = account();

		mockMvc.perform(put("/calendar/availability").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("[{\"dayOfWeek\":1,\"startsAt\":\"09:00\",\"endsAt\":\"12:00\","
					+ "\"timezone\":\"Marte/Olympus_Mons\"}]")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AVAILABILITY"));
	}

	@Test
	void aMalformedClockIs400WithCode() throws Exception {
		TestAuthHelper.AuthSession session = account();

		mockMvc.perform(put("/calendar/availability").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("[{\"dayOfWeek\":1,\"startsAt\":\"9h\",\"endsAt\":\"12:00\","
					+ "\"timezone\":\"America/Sao_Paulo\"}]")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AVAILABILITY"));
	}

	@Test
	void overlappingWindowsAre400AndLeaveNothingBehind() throws Exception {
		TestAuthHelper.AuthSession session = account();

		mockMvc.perform(put("/calendar/availability").header("X-Session-Token", session.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body("[{\"dayOfWeek\":2,\"startsAt\":\"09:00\",\"endsAt\":\"12:00\","
					+ "\"timezone\":\"America/Sao_Paulo\"},"
					+ "{\"dayOfWeek\":2,\"startsAt\":\"11:00\",\"endsAt\":\"13:00\","
					+ "\"timezone\":\"America/Sao_Paulo\"}]")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_AVAILABILITY"));

		mockMvc.perform(get("/calendar/availability").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

}
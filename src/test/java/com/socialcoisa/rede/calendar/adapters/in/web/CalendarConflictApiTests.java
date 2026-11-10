package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.TestAuthHelper;

import tools.jackson.databind.ObjectMapper;

/**
 * Contrato HTTP do aviso de conflito: sem sessão é 401, a janela é obrigatória (mesmo 400 da
 * leitura da agenda), e a lista nunca traz compromisso de outra pessoa.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarConflictApiTests {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
	private static final Instant FROM = Instant.parse("2027-04-01T12:00:00Z");
	private static final Instant TO = FROM.plus(Duration.ofHours(1));

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	private TestAuthHelper.AuthSession account() throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate("conf_api_" + UUID.randomUUID());
	}

	private String saveEntry(String ownerId, String title) {
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), title, null, FROM, TO, ZONE, false, Instant.now()));
		return id;
	}

	@Test
	void withoutSessionItIs401() throws Exception {
		mockMvc.perform(get("/calendar/entries/conflicts").param("from", FROM.toString())
			.param("to", TO.toString()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void theReaderSeesOnlyTheirOwnConflicts() throws Exception {
		TestAuthHelper.AuthSession owner = account();
		TestAuthHelper.AuthSession stranger = account();
		String mine = saveEntry(owner.userId(), "Minha");
		saveEntry(stranger.userId(), "Alheia");

		mockMvc.perform(get("/calendar/entries/conflicts")
			.header("X-Session-Token", owner.token())
			.param("from", FROM.toString())
			.param("to", TO.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].entry.id").value(mine))
			.andExpect(jsonPath("$[0].owner").value(true));
	}

	@Test
	void theEntryUnderEditionCanBeExcluded() throws Exception {
		TestAuthHelper.AuthSession owner = account();
		String id = saveEntry(owner.userId(), "Em edição");

		mockMvc.perform(get("/calendar/entries/conflicts")
			.header("X-Session-Token", owner.token())
			.param("from", FROM.toString())
			.param("to", TO.toString())
			.param("entryId", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void anEmptyWindowIs400() throws Exception {
		TestAuthHelper.AuthSession owner = account();

		mockMvc.perform(get("/calendar/entries/conflicts")
			.header("X-Session-Token", owner.token())
			.param("from", FROM.toString())
			.param("to", FROM.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
	}

}
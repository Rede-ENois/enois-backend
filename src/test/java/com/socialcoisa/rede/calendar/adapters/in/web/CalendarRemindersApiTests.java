package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * Contrato HTTP dos lembretes: sem sessão é 401, compromisso de outra pessoa responde 404 como se
 * não existisse (D10), antecedência fora da lista é 400 com código, e a leitura só mostra o que é
 * da própria pessoa.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarRemindersApiTests {

	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	private TestAuthHelper.AuthSession account(String userId) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(userId);
	}

	private String saveEntry(String ownerId, Instant startsAt) {
		String id = "cal_" + UUID.randomUUID().toString().replace("-", "");
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), "Com lembrete", null, startsAt,
			startsAt.plus(Duration.ofMinutes(30)), ZONE, false, Instant.now()));
		return id;
	}

	@Test
	void settingWithoutSessionIs401() throws Exception {
		String entryId = saveEntry("nobody_" + UUID.randomUUID(), Instant.now().plus(Duration.ofHours(2)));
		mockMvc.perform(put("/calendar/entries/{id}/reminders", entryId).param("minutesBefore", "15"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void theReaderSetsAndReadsTheirOwnReminder() throws Exception {
		TestAuthHelper.AuthSession session = account("rem_api_" + UUID.randomUUID());
		// O dono da entrada é o id que o banco deu à conta, não o apelido do teste: os dois divergem
		// e é exatamente essa igualdade que o serviço confere antes de aceitar um lembrete.
		String entryId = saveEntry(session.userId(), Instant.now().plus(Duration.ofHours(2)));

		mockMvc.perform(put("/calendar/entries/{id}/reminders", entryId)
				.param("minutesBefore", "30")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk());

		// Repetir não duplica: devolve a mesma linha.
		mockMvc.perform(put("/calendar/entries/{id}/reminders", entryId)
				.param("minutesBefore", "30")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.minutesBefore").value(30));

		mockMvc.perform(get("/calendar/entries/{id}/reminders", entryId)
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));

		mockMvc.perform(delete("/calendar/entries/{id}/reminders", entryId)
				.param("minutesBefore", "30")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk());

		mockMvc.perform(get("/calendar/entries/{id}/reminders", entryId)
				.header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void aStrangersEntryIs404LikeANonexistentOne() throws Exception {
		String entryId = saveEntry("rem_host_" + UUID.randomUUID(), Instant.now().plus(Duration.ofHours(2)));
		TestAuthHelper.AuthSession session = account("rem_api_" + UUID.randomUUID());

		mockMvc.perform(put("/calendar/entries/{id}/reminders", entryId)
				.param("minutesBefore", "15")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));
	}

	@Test
	void aLeadOffTheListIs400WithCode() throws Exception {
		TestAuthHelper.AuthSession session = account("rem_api_" + UUID.randomUUID());
		String entryId = saveEntry(session.userId(), Instant.now().plus(Duration.ofHours(2)));

		mockMvc.perform(put("/calendar/entries/{id}/reminders", entryId)
				.param("minutesBefore", "7")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REMINDER"));

		mockMvc.perform(put("/calendar/entries/{id}/reminders", entryId)
				.header("X-Session-Token", session.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REMINDER"));
	}

	@Test
	void aCancelledEntryRefusesNewReminders() throws Exception {
		TestAuthHelper.AuthSession session = account("rem_api_" + UUID.randomUUID());
		String entryId = saveEntry(session.userId(), Instant.now().plus(Duration.ofHours(2)));
		CalendarEntry entry = entries.findById(entryId).orElseThrow();
		entry.cancel(Instant.now());
		entries.save(entry);

		mockMvc.perform(put("/calendar/entries/{id}/reminders", entryId)
				.param("minutesBefore", "15")
				.header("X-Session-Token", session.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_CANCELLED"));
	}

}
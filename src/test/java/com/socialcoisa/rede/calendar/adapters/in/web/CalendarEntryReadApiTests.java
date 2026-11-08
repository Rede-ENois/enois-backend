package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
@AutoConfigureMockMvc
class CalendarEntryReadApiTests {

	private static final Instant FROM = Instant.parse("2026-12-01T00:00:00Z");
	private static final Instant TO = Instant.parse("2026-12-08T00:00:00Z");
	private static final Instant CREATED_AT = Instant.parse("2026-11-08T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	private TestAuthHelper.AuthSession account(String userId) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(userId);
	}

	private String saveEntry(String ownerId, CalendarScope scope, String title, Instant startsAt, Instant endsAt) {
		String id = UUID.randomUUID().toString();
		entries.save(CalendarEntry.create(id, ownerId, scope, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), title, null, startsAt, endsAt, ZONE, false, CREATED_AT));
		return id;
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder listAs(String token) {
		return get("/calendar/entries").header("X-Session-Token", token)
			.param("from", FROM.toString())
			.param("to", TO.toString());
	}

	@Test
	void semSessaoResponde401NasDuasRotas() throws Exception {
		mockMvc.perform(get("/calendar/entries").param("from", FROM.toString()).param("to", TO.toString()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		mockMvc.perform(get("/calendar/entries/{entryId}", UUID.randomUUID().toString()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void listaSomenteAsEntradasDoDono() throws Exception {
		var owner = account("cal_read_owner");
		var stranger = account("cal_read_stranger");
		String first = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Cafe",
			FROM.plusSeconds(3_600), FROM.plusSeconds(7_200));
		String second = saveEntry(owner.userId(), CalendarScope.PROFESSIONAL, "Entrevista",
			FROM.plusSeconds(10_800), FROM.plusSeconds(14_400));
		String foreign = saveEntry(stranger.userId(), CalendarScope.SOCIAL, "Alheia",
			FROM.plusSeconds(3_600), FROM.plusSeconds(7_200));

		mockMvc.perform(listAs(owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[*].entry.id", containsInAnyOrder(first, second)));

		mockMvc.perform(listAs(stranger.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].entry.id").value(foreign));
	}

	@Test
	void filtraPelaAgendaEPelaConsolidada() throws Exception {
		var owner = account("cal_read_scope");
		String social = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Festa",
			FROM.plusSeconds(3_600), FROM.plusSeconds(7_200));
		String professional = saveEntry(owner.userId(), CalendarScope.PROFESSIONAL, "Reuniao",
			FROM.plusSeconds(10_800), FROM.plusSeconds(14_400));

		mockMvc.perform(listAs(owner.token()).param("scope", "SOCIAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].entry.id").value(social))
			.andExpect(jsonPath("$[0].entry.scope").value("SOCIAL"))
			.andExpect(jsonPath("$[0].owner").value(true))
			.andExpect(jsonPath("$[0].participation").value(nullValue()));

		mockMvc.perform(listAs(owner.token()).param("scope", "PROFESSIONAL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].entry.id").value(professional));

		mockMvc.perform(listAs(owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[*].entry.id", containsInAnyOrder(social, professional)));
	}

	@Test
	void janelaEhMeiaAbertaDosDoisLados() throws Exception {
		var owner = account("cal_read_window");
		saveEntry(owner.userId(), CalendarScope.SOCIAL, "Termina no inicio",
			FROM.minusSeconds(7_200), FROM);
		saveEntry(owner.userId(), CalendarScope.SOCIAL, "Comeca no fim",
			TO, TO.plusSeconds(3_600));
		String inside = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Dentro",
			FROM.plusSeconds(3_600), FROM.plusSeconds(7_200));
		String spanning = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Atravessa",
			FROM.minusSeconds(3_600), FROM.plusSeconds(3_600));

		mockMvc.perform(listAs(owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[*].entry.id", containsInAnyOrder(inside, spanning)));
	}

	@Test
	void entradaAlheiaRespondeIgualAInexistente() throws Exception {
		var owner = account("cal_read_private_owner");
		var stranger = account("cal_read_private_stranger");
		String id = saveEntry(owner.userId(), CalendarScope.PROFESSIONAL, "Sigilosa",
			FROM.plusSeconds(3_600), FROM.plusSeconds(7_200));

		mockMvc.perform(get("/calendar/entries/{entryId}", id).header("X-Session-Token", stranger.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));

		mockMvc.perform(get("/calendar/entries/{entryId}", UUID.randomUUID().toString())
				.header("X-Session-Token", stranger.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));

		mockMvc.perform(get("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id));
	}

	@Test
	void detalheTrazOsCamposDoContratoSemExporODono() throws Exception {
		var owner = account("cal_read_detail");
		String id = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Aniversario",
			FROM.plusSeconds(3_600), FROM.plusSeconds(10_800));

		mockMvc.perform(get("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.scope").value("SOCIAL"))
			.andExpect(jsonPath("$.kind").value("PERSONAL"))
			.andExpect(jsonPath("$.title").value("Aniversario"))
			.andExpect(jsonPath("$.startsAt").value(FROM.plusSeconds(3_600).toString()))
			.andExpect(jsonPath("$.endsAt").value(FROM.plusSeconds(10_800).toString()))
			.andExpect(jsonPath("$.timeZoneId").value("America/Sao_Paulo"))
			.andExpect(jsonPath("$.allDay").value(false))
			.andExpect(jsonPath("$.state").value("ACTIVE"))
			.andExpect(jsonPath("$.ownerId").doesNotExist());
	}

	@Test
	void janelaOuAgendaInvalidaResponde400() throws Exception {
		var owner = account("cal_read_invalid");

		mockMvc.perform(get("/calendar/entries").header("X-Session-Token", owner.token())
				.param("from", TO.toString()).param("to", FROM.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_WINDOW"));

		mockMvc.perform(get("/calendar/entries").header("X-Session-Token", owner.token())
				.param("from", FROM.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_WINDOW"));

		mockMvc.perform(get("/calendar/entries").header("X-Session-Token", owner.token())
				.param("from", "ontem").param("to", TO.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_WINDOW"));

		mockMvc.perform(listAs(owner.token()).param("scope", "HOBBY"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
	}

	@Test
	void compromissoCanceladoApareceComEstado() throws Exception {
		var owner = account("cal_read_cancelled");
		String id = UUID.randomUUID().toString();
		CalendarEntry entry = CalendarEntry.create(id, owner.userId(), CalendarScope.SOCIAL,
			CalendarKind.PERSONAL, new CalendarEntrySource(null, null), "Cancelado", null,
			FROM.plusSeconds(3_600), FROM.plusSeconds(7_200), ZONE, false, CREATED_AT);
		entry.cancel(Instant.parse("2026-11-09T00:00:00Z"));
		entries.save(entry);

		mockMvc.perform(listAs(owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].entry.id").value(id))
			.andExpect(jsonPath("$[0].entry.state").value("CANCELLED"))
			.andExpect(jsonPath("$[0].entry.cancelledAt").value("2026-11-09T00:00:00Z"));
	}

	@Test
	void aliasV1ServeOMesmoContrato() throws Exception {
		var owner = account("cal_read_v1");
		String id = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Pelo alias",
			FROM.plusSeconds(3_600), FROM.plusSeconds(7_200));

		mockMvc.perform(get("/v1/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id));
	}
}

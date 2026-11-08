package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
class CalendarDetailAndPendingInvitationsApiTests {

	private static final Instant STARTS_AT = Instant.parse("2026-12-20T22:00:00Z");
	private static final Instant ENDS_AT = Instant.parse("2026-12-20T23:00:00Z");
	private static final Instant CREATED_AT = Instant.parse("2026-11-08T00:00:00Z");
	private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

	@Autowired MockMvc mockMvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired CalendarEntryRepository entries;

	private TestAuthHelper.AuthSession account(String userId) throws Exception {
		return new TestAuthHelper(mockMvc, objectMapper).authenticate(userId);
	}

	private String usernameOf(TestAuthHelper.AuthSession session) throws Exception {
		String body = mockMvc.perform(get("/me").header("X-Session-Token", session.token()))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return objectMapper.readTree(body).get("username").asString();
	}

	private String saveEntry(String ownerId, String title) {
		String id = UUID.randomUUID().toString();
		entries.save(CalendarEntry.create(id, ownerId, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), title, null, STARTS_AT, ENDS_AT, ZONE, false, CREATED_AT));
		return id;
	}

	private void invite(TestAuthHelper.AuthSession owner, String entryId, String username, String key)
			throws Exception {
		mockMvc
			.perform(post("/calendar/entries/{entryId}/participants", entryId)
				.header("X-Session-Token", owner.token())
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("username", username))))
			.andExpect(status().isCreated());
	}

	@Test
	void detalheDoConvidadoTrazOCompromissoComOssoEstadoESemALista() throws Exception {
		var owner = account("cal_det_owner");
		var guest = account("cal_det_guest");
		String entryId = saveEntry(owner.userId(), "Festa do time");
		invite(owner, entryId, usernameOf(guest), "det-1");

		mockMvc.perform(get("/calendar/entries/{entryId}", entryId).header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(entryId))
			.andExpect(jsonPath("$.entry.title").value("Festa do time"))
			.andExpect(jsonPath("$.entry.ownerId").doesNotExist())
			.andExpect(jsonPath("$.owner").value(false))
			.andExpect(jsonPath("$.participation").value("INVITED"))
			.andExpect(jsonPath("$.participants", nullValue()));

		mockMvc
			.perform(patch("/calendar/entries/{entryId}/participants/me", entryId)
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "det-2")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"DECLINED\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/calendar/entries/{entryId}", entryId).header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.owner").value(false))
			.andExpect(jsonPath("$.participation").value("DECLINED"));

		// O organizador continua vendo a lista, e ela traz quem foi convidado.
		mockMvc.perform(get("/calendar/entries/{entryId}", entryId).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.owner").value(true))
			.andExpect(jsonPath("$.participants", hasSize(1)))
			.andExpect(jsonPath("$.participants[0].username").value(usernameOf(guest)))
			.andExpect(jsonPath("$.participants[0].state").value("DECLINED"));
	}

	@Test
	void quemNemFoiConvidadoRespondeO4o4DeIdInexistente() throws Exception {
		var owner = account("cal_det_stranger_owner");
		var stranger = account("cal_det_stranger");
		String entryId = saveEntry(owner.userId(), "So do dono");

		mockMvc.perform(get("/calendar/entries/{entryId}", entryId).header("X-Session-Token", stranger.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));

		mockMvc
			.perform(get("/calendar/entries/{entryId}", UUID.randomUUID().toString())
				.header("X-Session-Token", stranger.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CALENDAR_ENTRY_NOT_FOUND"));
	}

	@Test
	void convitesPendentesListamSoOQueFaltaResponder() throws Exception {
		var owner = account("cal_pend_owner");
		var guest = account("cal_pend_guest");
		String pending = saveEntry(owner.userId(), "Aguardando resposta");
		String accepted = saveEntry(owner.userId(), "Ja aceitei");
		String handle = usernameOf(guest);

		invite(owner, pending, handle, "pend-1");
		invite(owner, accepted, handle, "pend-2");
		mockMvc
			.perform(patch("/calendar/entries/{entryId}/participants/me", accepted)
				.header("X-Session-Token", guest.token())
				.header("Idempotency-Key", "pend-3")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"state\":\"ACCEPTED\"}"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/calendar/invitations").header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(pending))
			.andExpect(jsonPath("$[0].title").value("Aguardando resposta"));

		// O organizador não tem convite pendente: convite é coisa de quem foi convidado.
		mockMvc.perform(get("/calendar/invitations").header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void conviteDeEntradaCanceladaSaiDaListaDePendentes() throws Exception {
		var owner = account("cal_pend_cancel_owner");
		var guest = account("cal_pend_cancel_guest");
		String entryId = saveEntry(owner.userId(), "Vai ser cancelado");
		invite(owner, entryId, usernameOf(guest), "pendc-1");

		mockMvc.perform(get("/calendar/invitations").header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)));

		mockMvc.perform(delete("/calendar/entries/{entryId}", entryId)
			.header("X-Session-Token", owner.token())
			.header("Idempotency-Key", "pendc-2")).andExpect(status().isOk());

		mockMvc.perform(get("/calendar/invitations").header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void convitesPendentesSemSessaoResponde401() throws Exception {
		mockMvc.perform(get("/calendar/invitations"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void convitesPendentesFuncionamPeloAliasV1() throws Exception {
		var owner = account("cal_pend_v1_owner");
		var guest = account("cal_pend_v1_guest");
		String entryId = saveEntry(owner.userId(), "Pela v1");
		invite(owner, entryId, usernameOf(guest), "pendv-1");

		mockMvc.perform(get("/v1/calendar/invitations").header("X-Session-Token", guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].id").value(entryId));
	}
}

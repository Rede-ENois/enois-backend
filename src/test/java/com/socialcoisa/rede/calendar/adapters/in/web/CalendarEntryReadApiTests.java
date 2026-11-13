package com.socialcoisa.rede.calendar.adapters.in.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
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
	@Autowired CalendarParticipantRepository participants;

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
			.andExpect(jsonPath("$.entry.id").value(id))
			.andExpect(jsonPath("$.owner").value(true));
	}

	@Test
	void detalheTrazOsCamposDoContratoSemExporODono() throws Exception {
		var owner = account("cal_read_detail");
		String id = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Aniversario",
			FROM.plusSeconds(3_600), FROM.plusSeconds(10_800));

		mockMvc.perform(get("/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(id))
			.andExpect(jsonPath("$.entry.scope").value("SOCIAL"))
			.andExpect(jsonPath("$.entry.kind").value("PERSONAL"))
			.andExpect(jsonPath("$.entry.title").value("Aniversario"))
			.andExpect(jsonPath("$.entry.startsAt").value(FROM.plusSeconds(3_600).toString()))
			.andExpect(jsonPath("$.entry.endsAt").value(FROM.plusSeconds(10_800).toString()))
			.andExpect(jsonPath("$.entry.timeZoneId").value("America/Sao_Paulo"))
			.andExpect(jsonPath("$.entry.allDay").value(false))
			.andExpect(jsonPath("$.entry.state").value("ACTIVE"))
			.andExpect(jsonPath("$.entry.ownerId").doesNotExist())
			.andExpect(jsonPath("$.owner").value(true))
			.andExpect(jsonPath("$.participation").value(nullValue()))
			.andExpect(jsonPath("$.participants", hasSize(0)));
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
	void janelaMaiorQueUmAnoResponde400NasLeiturasPorIntervalo() throws Exception {
		var owner = account("cal_read_long_window");
		String wide = FROM.minus(Duration.ofDays(400)).toString();

		mockMvc.perform(get("/calendar/entries").header("X-Session-Token", owner.token())
				.param("from", wide).param("to", TO.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("WINDOW_TOO_LONG"));

		mockMvc.perform(get("/calendar/entries/conflicts").header("X-Session-Token", owner.token())
				.param("from", wide).param("to", TO.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("WINDOW_TOO_LONG"));

		// O teto é o mesmo da assinatura: a janela de um ano da assinatura continua cabendo.
		mockMvc.perform(get("/calendar/entries").header("X-Session-Token", owner.token())
				.param("from", FROM.minus(Duration.ofDays(30)).toString())
				.param("to", TO.plus(Duration.ofDays(329)).toString()))
			.andExpect(status().isOk());
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

	/**
	 * O convidado enxerga o compromisso alheio na própria lista, com o estado do convite dele e o
	 * estado do compromisso. Cancelado continua na lista: a agenda é o histórico de respostas
	 * (D13), e esconder a linha esconderia também o cancelamento que o organizador publicou.
	 */
	@Test
	void convidadoVeoCompromissoNaPropriaListaComARespostaDele() throws Exception {
		var owner = account("cal_read_inv_owner");
		var guest = account("cal_read_inv_guest");
		var other = account("cal_read_inv_other");
		String entryId = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Festa", FROM.plusSeconds(3_600),
			FROM.plusSeconds(7_200));
		inviteDirectly(owner.userId(), entryId, guest.userId());
		respondDirectly(entryId, guest.userId(), com.socialcoisa.rede.calendar.domain.CalendarParticipantState.ACCEPTED);
		saveEntry(owner.userId(), CalendarScope.SOCIAL, "Cancelada", FROM.plusSeconds(10_800),
			FROM.plusSeconds(14_400));
		String cancelledId = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Cancelada pós convite",
			FROM.plusSeconds(10_800), FROM.plusSeconds(14_400));
		inviteDirectly(owner.userId(), cancelledId, guest.userId());
		CalendarEntry cancelled = entries.findById(cancelledId).orElseThrow();
		cancelled.cancel(Instant.parse("2026-11-09T00:00:00Z"));
		entries.save(cancelled);

		mockMvc.perform(listAs(guest.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[?(@.entry.id == '%s')].owner".formatted(entryId)).value(false))
			.andExpect(jsonPath("$[?(@.entry.id == '%s')].participation".formatted(entryId)).value("ACCEPTED"))
			.andExpect(jsonPath("$[?(@.entry.id == '%s')].entry.state".formatted(cancelledId)).value("CANCELLED"));

		// Quem não foi convidado não vê nada do compromisso alheio na lista.
		mockMvc.perform(listAs(other.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(0)));
	}

	private void inviteDirectly(String ownerId, String entryId, String guestId) {
		participants.save(com.socialcoisa.rede.calendar.domain.CalendarParticipant
			.invite("p_" + UUID.randomUUID(), entryId, ownerId, guestId, CREATED_AT));
	}

	private void respondDirectly(String entryId, String guestId,
			com.socialcoisa.rede.calendar.domain.CalendarParticipantState state) {
		var participant = participants.findByEntryAndUser(entryId, guestId).orElseThrow();
		participant.respond(state, CREATED_AT);
		participants.save(participant);
	}

	@Test
	void aliasV1ServeOMesmoContrato() throws Exception {
		var owner = account("cal_read_v1");
		String id = saveEntry(owner.userId(), CalendarScope.SOCIAL, "Pelo alias",
			FROM.plusSeconds(3_600), FROM.plusSeconds(7_200));

		mockMvc.perform(get("/v1/calendar/entries/{entryId}", id).header("X-Session-Token", owner.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entry.id").value(id));
	}
}

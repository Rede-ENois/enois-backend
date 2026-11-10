package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.calendar.application.CalendarConflictService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Conflito de horário contra o banco de verdade.
 *
 * <p>O que este teste tranca: a checagem usa a mesma agenda da tela (D13) — o que a pessoa organiza
 * mais o que ela aceitou ou marcou como "talvez" —, convite pendente e recusado não ocupam horário,
 * compromisso cancelado não conflita, encostar no fim não colide (intervalo meio-aberto), e a
 * edição pode ignorar a própria entrada.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarConflictServiceTests {

	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
	private static final Instant BASE = Instant.parse("2027-03-01T12:00:00Z");
	private static final Instant ONE_HOUR = BASE.plus(Duration.ofHours(1));

	@Autowired CalendarConflictService conflicts;
	@Autowired CalendarEntryRepository entries;
	@Autowired CalendarParticipantRepository participants;

	@Test
	void theOwnersOverlappingEntryIsAConflict() {
		String owner = user();
		saveEntry(owner, BASE, ONE_HOUR, "Reunião", CalendarScope.SOCIAL);
		saveEntry(owner, BASE.plus(Duration.ofHours(2)), BASE.plus(Duration.ofHours(3)), "Outra",
			CalendarScope.SOCIAL);

		assertThat(conflicts.conflicts(owner, BASE.plusSeconds(1_800), BASE.plusSeconds(5_400), null, null))
			.hasSize(1)
			.allSatisfy(item -> assertThat(item.entry().title()).isEqualTo("Reunião"));
		assertThat(conflicts.hasConflict(owner, BASE.plusSeconds(1_800), BASE.plusSeconds(5_400), null, null))
			.isTrue();
	}

	@Test
	void touchingTheOtherEntryAtTheEdgeIsNotAConflict() {
		String owner = user();
		saveEntry(owner, BASE, ONE_HOUR, "Antes", CalendarScope.SOCIAL);

		assertThat(conflicts.conflicts(owner, ONE_HOUR, ONE_HOUR.plus(Duration.ofHours(1)), null, null)).isEmpty();
		assertThat(conflicts.hasConflict(owner, ONE_HOUR, ONE_HOUR.plus(Duration.ofHours(1)), null, null))
			.isFalse();
	}

	@Test
	void aCancelledEntryDoesNotOccupyTime() {
		String owner = user();
		CalendarEntry entry = saveEntry(owner, BASE, ONE_HOUR, "Cancelada", CalendarScope.SOCIAL);
		entry.cancel(Instant.now());
		entries.save(entry);

		assertThat(conflicts.conflicts(owner, BASE, ONE_HOUR, null, null)).isEmpty();
	}

	@Test
	void theEditedEntryCanBeExcludedFromItsOwnCheck() {
		String owner = user();
		String id = saveEntry(owner, BASE, ONE_HOUR, "Em edição", CalendarScope.SOCIAL).id();

		assertThat(conflicts.conflicts(owner, BASE, ONE_HOUR, null, id)).isEmpty();
		assertThat(conflicts.conflicts(owner, BASE, ONE_HOUR, null, null)).hasSize(1);
	}

	@Test
	void anAcceptedOrTentativeInvitationOccupiesTimeAndAPendingOneDoesNot() {
		String owner = user();
		String accepted = user();
		String tentative = user();
		String pending = user();
		CalendarEntry entry = saveEntry(owner, BASE, ONE_HOUR, "Festa", CalendarScope.SOCIAL);

		invite(owner, entry.id(), accepted);
		invite(owner, entry.id(), tentative);
		invite(owner, entry.id(), pending);
		respond(accepted, entry.id(), CalendarParticipantState.ACCEPTED);
		respond(tentative, entry.id(), CalendarParticipantState.TENTATIVE);

		assertThat(conflicts.conflicts(accepted, BASE, ONE_HOUR, null, null)).hasSize(1);
		assertThat(conflicts.conflicts(tentative, BASE, ONE_HOUR, null, null)).hasSize(1);
		assertThat(conflicts.conflicts(pending, BASE, ONE_HOUR, null, null)).isEmpty();
	}

	@Test
	void aDeclinedInvitationDoesNotOccupyTime() {
		String owner = user();
		String guest = user();
		CalendarEntry entry = saveEntry(owner, BASE, ONE_HOUR, "Café", CalendarScope.SOCIAL);

		invite(owner, entry.id(), guest);
		respond(guest, entry.id(), CalendarParticipantState.DECLINED);

		assertThat(conflicts.conflicts(guest, BASE, ONE_HOUR, null, null)).isEmpty();
	}

	private void respond(String userId, String entryId, CalendarParticipantState state) {
		CalendarParticipant participant = participants.findByEntryAndUser(entryId, userId).orElseThrow();
		participant.respond(state, Instant.now());
		participants.save(participant);
	}

	@Test
	void theScopeFilterOnlyLooksAtTheRequestedAgenda() {
		String owner = user();
		saveEntry(owner, BASE, ONE_HOUR, "Social", CalendarScope.SOCIAL);
		saveEntry(owner, BASE, ONE_HOUR, "Profissional", CalendarScope.PROFESSIONAL);

		assertThat(conflicts.conflicts(owner, BASE, ONE_HOUR, CalendarScope.SOCIAL, null))
			.hasSize(1)
			.allSatisfy(item -> assertThat(item.entry().scope()).isEqualTo(CalendarScope.SOCIAL));
		assertThat(conflicts.conflicts(owner, BASE, ONE_HOUR, CalendarScope.PROFESSIONAL, null))
			.hasSize(1)
			.allSatisfy(item -> assertThat(item.entry().scope()).isEqualTo(CalendarScope.PROFESSIONAL));
		assertThat(conflicts.conflicts(owner, BASE, ONE_HOUR, null, null)).hasSize(2);
	}

	@Test
	void anotherPersonsAgendaIsNeverInTheResult() {
		String owner = user();
		String stranger = user();
		saveEntry(owner, BASE, ONE_HOUR, "Minha", CalendarScope.SOCIAL);
		saveEntry(stranger, BASE, ONE_HOUR, "Alheia", CalendarScope.SOCIAL);

		assertThat(conflicts.conflicts(owner, BASE, ONE_HOUR, null, null))
			.hasSize(1)
			.allSatisfy(item -> assertThat(item.entry().title()).isEqualTo("Minha"));
	}

	@Test
	void anEmptyWindowIsRejectedTheSameWayAsTheAgendaRead() {
		String owner = user();

		assertThatThrownBy(() -> conflicts.conflicts(owner, BASE, BASE, null, null))
			.isInstanceOf(ApiException.class);
	}

	private void invite(String owner, String entryId, String guest) {
		participants.save(CalendarParticipant.invite("p_" + UUID.randomUUID(), entryId, owner, guest,
			Instant.now()));
	}

	private static String user() {
		return "conf_" + UUID.randomUUID().toString().replace("-", "");
	}

	private CalendarEntry saveEntry(String owner, Instant startsAt, Instant endsAt, String title,
			CalendarScope scope) {
		CalendarEntry entry = CalendarEntry.create("cal_" + UUID.randomUUID().toString().replace("-", ""), owner,
			scope, CalendarKind.PERSONAL, new CalendarEntrySource(null, null), title, null, startsAt, endsAt,
			SAO_PAULO, false, Instant.now());
		entries.save(entry);
		return entry;
	}

}
package com.socialcoisa.rede.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarInvitationRepository;
import com.socialcoisa.rede.calendar.application.port.out.CalendarParticipantRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarParticipantState;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

import static org.assertj.core.api.Assertions.tuple;

/**
 * A entrada central contra o banco de verdade: o que a V257 criou tem de ser lido como o domínio
 * escreveu, e a consulta por janela tem de devolver só o que toca a janela.
 *
 * <p>O que este teste tranca: fuso e instante voltam iguais (nada de horário local achado no
 * caminho), a origem permite reencontrar a entrada que veio de outro módulo, a janela é meia-aberta
 * e o filtro de agenda separa as duas sem esconder a consolidada.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarEntryPersistenceTests {

	private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Autowired
	private CalendarEntryRepository repository;

	@Autowired
	private CalendarParticipantRepository participants;

	@Autowired
	private CalendarInvitationRepository invitations;

	@Test
	void storesAndReadsBackInstantZoneAndSource() {
		String owner = "cal_owner_" + UUID.randomUUID();
		CalendarEntry saved = entry(owner, CalendarScope.PROFESSIONAL, CalendarKind.MENTORING,
			new CalendarEntrySource("MENTORING", "ms_" + UUID.randomUUID()), NOW.plus(Duration.ofHours(1)),
			Duration.ofMinutes(50));

		repository.save(saved);
		CalendarEntry loaded = repository.findById(saved.id()).orElseThrow();

		assertThat(loaded.startsAt()).isEqualTo(saved.startsAt());
		assertThat(loaded.endsAt()).isEqualTo(saved.endsAt());
		assertThat(loaded.zone()).isEqualTo(SAO_PAULO);
		assertThat(loaded.scope()).isEqualTo(CalendarScope.PROFESSIONAL);
		assertThat(loaded.kind()).isEqualTo(CalendarKind.MENTORING);
		assertThat(loaded.source()).isEqualTo(saved.source());
		assertThat(loaded.active()).isTrue();
	}

	@Test
	void findsTheEntryByItsSource() {
		String owner = "cal_owner_" + UUID.randomUUID();
		CalendarEntrySource source = new CalendarEntrySource("MENTORING", "ms_" + UUID.randomUUID());
		CalendarEntry saved = entry(owner, CalendarScope.PROFESSIONAL, CalendarKind.MENTORING, source,
			NOW.plus(Duration.ofHours(1)), Duration.ofMinutes(50));
		repository.save(saved);

		assertThat(repository.findBySource(owner, source)).map(CalendarEntry::id).contains(saved.id());
		assertThat(repository.findBySource(owner, new CalendarEntrySource("MENTORING", "ms_outra"))).isEmpty();
		assertThat(repository.findBySource(owner, new CalendarEntrySource(null, null))).isEmpty();
	}

	@Test
	void windowQueryIsHalfOpenAndHonoursTheScopeFilter() {
		String owner = "cal_owner_" + UUID.randomUUID();
		Instant windowStart = NOW.plus(Duration.ofHours(10));
		Instant windowEnd = windowStart.plus(Duration.ofHours(1));

		// Começa dentro da janela.
		CalendarEntry inside = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), windowStart.plus(Duration.ofMinutes(15)), Duration.ofMinutes(30));
		// Atravessa a janela inteira.
		CalendarEntry spanning = entry(owner, CalendarScope.PROFESSIONAL, CalendarKind.MEETING,
			new CalendarEntrySource(null, null), windowStart.minus(Duration.ofHours(2)), Duration.ofHours(4));
		// Termina exatamente quando a janela começa: fora.
		CalendarEntry before = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), windowStart.minus(Duration.ofHours(1)), Duration.ofHours(1));
		// Começa exatamente quando a janela termina: fora.
		CalendarEntry after = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), windowEnd, Duration.ofHours(1));
		for (CalendarEntry e : new CalendarEntry[] { inside, spanning, before, after }) {
			repository.save(e);
		}

		// Ordem de início: quem atravessa a janela começou antes de quem nasce dentro dela.
		assertThat(repository.findInWindow(owner, null, windowStart, windowEnd))
			.extracting(CalendarEntry::id)
			.containsExactly(spanning.id(), inside.id());
		assertThat(repository.findInWindow(owner, CalendarScope.SOCIAL, windowStart, windowEnd))
			.extracting(CalendarEntry::id)
			.containsExactly(inside.id());
		assertThat(repository.findInWindow(owner, CalendarScope.PROFESSIONAL, windowStart, windowEnd))
			.extracting(CalendarEntry::id)
			.containsExactly(spanning.id());
	}

	@Test
	void cancelledEntriesStillShowUpInTheWindow() {
		String owner = "cal_owner_" + UUID.randomUUID();
		CalendarEntry entry = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), NOW.plus(Duration.ofHours(1)), Duration.ofMinutes(30));
		entry.cancel(NOW.plus(Duration.ofMinutes(10)));
		repository.save(entry);

		assertThat(repository.findInWindow(owner, null, NOW, NOW.plus(Duration.ofDays(1))))
			.singleElement()
			.satisfies(loaded -> {
				assertThat(loaded.active()).isFalse();
				assertThat(loaded.cancelledAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
			});
	}

	@Test
	void anotherOwnersEntriesNeverAppear() {
		String owner = "cal_owner_" + UUID.randomUUID();
		repository.save(entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL, new CalendarEntrySource(null, null),
			NOW.plus(Duration.ofHours(1)), Duration.ofMinutes(30)));

		assertThat(repository.findInWindow("cal_owner_outro_" + UUID.randomUUID(), null, NOW,
				NOW.plus(Duration.ofDays(1)))).isEmpty();
	}

/**
	 * A agenda de quem foi convidado sai do cruzamento entre convite e compromisso, não de uma
	 * lista de ids carregada antes. O que este teste tranca: o convite traz o compromisso do dono
	 * com a resposta junto, respeita a janela meia-aberta e o filtro de agenda, e não traz
	 * compromisso de quem não convidou a pessoa.
	 *
	 * <p>O pendente e o cancelado continuam aparecendo: a tela mostra o convite esperando resposta
	 * e o export publica o cancelamento no mesmo `UID` (D6/D13). Esse detalhe é o que este teste
	 * protege contra um filtro de estado colocado no cruzamento por engano.
	 */
	@Test
	void invitedEntriesComeFromTheParticipantJoinWithTheirAnswer() {
		String owner = "cal_owner_" + UUID.randomUUID();
		String guest = "cal_guest_" + UUID.randomUUID();
		String stranger = "cal_stranger_" + UUID.randomUUID();
		Instant windowStart = NOW.plus(Duration.ofHours(10));
		Instant windowEnd = windowStart.plus(Duration.ofHours(1));

		CalendarEntry social = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), windowStart.plus(Duration.ofMinutes(15)), Duration.ofMinutes(30));
		CalendarEntry professional = entry(owner, CalendarScope.PROFESSIONAL, CalendarKind.MEETING,
			new CalendarEntrySource(null, null), windowStart.plus(Duration.ofMinutes(20)), Duration.ofMinutes(30));
		// Fora da janela: o convite existe, mas o compromisso não toca o intervalo pedido.
		CalendarEntry outside = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), windowEnd.plus(Duration.ofHours(2)), Duration.ofMinutes(30));
		// Cancelado depois do convite: continua na leitura, com a resposta que a pessoa deu.
		CalendarEntry cancelled = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), windowStart.plus(Duration.ofMinutes(25)), Duration.ofMinutes(30));
		cancelled.cancel(NOW);
		for (CalendarEntry e : new CalendarEntry[] { social, professional, outside, cancelled }) {
			repository.save(e);
			participants.save(CalendarParticipant.invite("cal_p_" + UUID.randomUUID(), e.id(), owner, guest, NOW));
		}
		CalendarParticipant accepted = participants.findByEntryAndUser(social.id(), guest).orElseThrow();
		accepted.respond(CalendarParticipantState.ACCEPTED, NOW);
		participants.save(accepted);

		assertThat(invitations.findInvited(guest, null, windowStart, windowEnd))
			.extracting(invited -> invited.entry().id(), CalendarInvitationRepository.InvitedEntry::answer)
			.containsExactly(
				tuple(social.id(), CalendarParticipantState.ACCEPTED),
				tuple(professional.id(), CalendarParticipantState.INVITED),
				tuple(cancelled.id(), CalendarParticipantState.INVITED));
		assertThat(invitations.findInvited(guest, CalendarScope.PROFESSIONAL, windowStart, windowEnd))
			.extracting(invited -> invited.entry().id())
			.containsExactly(professional.id());
		// Quem não foi convidado não herda a agenda de ninguém.
		assertThat(invitations.findInvited(stranger, null, windowStart, windowEnd)).isEmpty();
		// O dono não vê o próprio compromisso pela via do convite: ele já vem pela consulta de dono.
		assertThat(invitations.findInvited(owner, null, windowStart, windowEnd)).isEmpty();
	}

	/**
	 * A fila de convites a responder sai do mesmo cruzamento: só o convite sem resposta para um
	 * compromisso ativo, na ordem em que os convites foram feitos. Este teste falharia no código
	 * anterior, que carregava a lista de pendentes para então devolver os ids num `in` sem teto.
	 */
	@Test
	void pendingInvitationsComeFromTheParticipantJoin() {
		String owner = "cal_owner_" + UUID.randomUUID();
		String guest = "cal_guest_" + UUID.randomUUID();
		String other = "cal_other_" + UUID.randomUUID();

		Instant firstInvite = NOW.minus(Duration.ofHours(3));
		Instant secondInvite = NOW.minus(Duration.ofHours(1));
		CalendarEntry older = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), NOW.plus(Duration.ofHours(10)), Duration.ofMinutes(30));
		CalendarEntry newer = entry(owner, CalendarScope.PROFESSIONAL, CalendarKind.MEETING,
			new CalendarEntrySource(null, null), NOW.plus(Duration.ofHours(12)), Duration.ofMinutes(30));
		// Convite respondido e entrada cancelada existem como linhas, mas não voltam na fila.
		CalendarEntry answered = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), NOW.plus(Duration.ofHours(14)), Duration.ofMinutes(30));
		CalendarEntry cancelled = entry(owner, CalendarScope.SOCIAL, CalendarKind.PERSONAL,
			new CalendarEntrySource(null, null), NOW.plus(Duration.ofHours(16)), Duration.ofMinutes(30));
		cancelled.cancel(NOW);
		repository.save(older);
		repository.save(newer);
		repository.save(answered);
		repository.save(cancelled);
		participants.save(CalendarParticipant.invite("cal_p_" + UUID.randomUUID(), older.id(), owner, guest,
			firstInvite));
		participants.save(CalendarParticipant.invite("cal_p_" + UUID.randomUUID(), newer.id(), owner, guest,
			secondInvite));
		CalendarParticipant replied = CalendarParticipant.invite("cal_p_" + UUID.randomUUID(), answered.id(),
			owner, guest, secondInvite);
		replied.respond(CalendarParticipantState.ACCEPTED, NOW);
		participants.save(replied);
		participants.save(CalendarParticipant.invite("cal_p_" + UUID.randomUUID(), cancelled.id(), owner, guest,
			secondInvite));
		// Convite do mesmo compromisso para outra pessoa: entra na fila dela, não nesta.
		participants.save(CalendarParticipant.invite("cal_p_" + UUID.randomUUID(), older.id(), owner, other,
			secondInvite));

		assertThat(repository.findPendingInvitations(guest))
			.extracting(CalendarEntry::id)
			.containsExactly(older.id(), newer.id());
		assertThat(repository.findPendingInvitations(other))
			.extracting(CalendarEntry::id)
			.containsExactly(older.id());
		// Quem organizou não tem convite pendente na própria agenda.
		assertThat(repository.findPendingInvitations(owner)).isEmpty();
	}

	private static CalendarEntry entry(String owner, CalendarScope scope, CalendarKind kind,
			CalendarEntrySource source, Instant startsAt, Duration length) {
		return CalendarEntry.create("cal_" + UUID.randomUUID(), owner, scope, kind, source,
			"Compromisso " + UUID.randomUUID().toString().substring(0, 6), null, startsAt,
			startsAt.plus(length), SAO_PAULO, false, NOW);
	}

}

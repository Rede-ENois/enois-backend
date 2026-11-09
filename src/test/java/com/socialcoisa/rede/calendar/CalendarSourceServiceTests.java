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

import com.socialcoisa.rede.calendar.application.CalendarSourceService;
import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarKind;
import com.socialcoisa.rede.calendar.domain.CalendarScope;

/**
 * Entrada gravada por outro módulo, com a origem preenchida, contra o banco de verdade.
 *
 * <p>O que este teste tranca é a D3: a mesma origem não vira duas linhas. Gravar de novo tem de
 * mover a entrada que já existe, e isso precisa valer mesmo quando a chamada chega duas vezes ao
 * mesmo tempo, que é o caso do duplo clique. O banco é o juiz, não uma checagem em memória.
 *
 * <p>Cancelar também passa pela origem: a linha fica, com a data, para o `.ics` publicar o
 * cancelamento. E entrada cancelada não ressuscita num upsert posterior.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CalendarSourceServiceTests {

	private static final Instant STARTS_AT = Instant.parse("2026-11-20T22:00:00Z");
	private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

	@Autowired
	private CalendarSourceService service;

	@Autowired
	private CalendarEntryRepository entries;

	@Test
	void creatingTheSameSourceTwiceKeepsOneEntry() {
		String owner = owner();
		String sourceId = "mentor_session_" + UUID.randomUUID();

		CalendarSourceService.UpsertResult first = service.upsert(input(owner, sourceId, STARTS_AT));
		CalendarSourceService.UpsertResult second = service.upsert(input(owner, sourceId, STARTS_AT));

		assertThat(first.created()).isTrue();
		assertThat(second.created()).isFalse();
		assertThat(second.moved()).isFalse();
		assertThat(second.entry().id()).isEqualTo(first.entry().id());
		assertThat(entries.findInWindow(owner, null, STARTS_AT.minus(Duration.ofDays(1)),
				STARTS_AT.plus(Duration.ofDays(1)))).hasSize(1);
	}

	@Test
	void aNewStartMovesTheSameEntryInsteadOfDuplicating() {
		String owner = owner();
		String sourceId = "mentor_session_" + UUID.randomUUID();
		CalendarEntry original = service.upsert(input(owner, sourceId, STARTS_AT)).entry();

		Instant movedTo = STARTS_AT.plus(Duration.ofDays(2));
		CalendarSourceService.UpsertResult moved = service.upsert(input(owner, sourceId, movedTo));

		assertThat(moved.created()).isFalse();
		assertThat(moved.moved()).isTrue();
		assertThat(moved.entry().id()).isEqualTo(original.id());
		assertThat(entries.findById(original.id()).orElseThrow().startsAt()).isEqualTo(movedTo);
		// A entrada antiga saiu da janela, então a agenda não mostra o horário velho.
		assertThat(entries.findInWindow(owner, null, STARTS_AT.minus(Duration.ofHours(1)),
				STARTS_AT.plus(Duration.ofHours(1)))).isEmpty();
	}

	@Test
	void theDatabaseRefusesASecondRowForTheSameSource() {
		String owner = owner();
		CalendarEntrySource source = new CalendarEntrySource("MENTORING", "mentor_session_" + UUID.randomUUID());
		Instant now = Instant.parse("2026-11-08T12:00:00Z");
		entries.save(CalendarEntry.create("cal_" + UUID.randomUUID(), owner, CalendarScope.PROFESSIONAL,
			CalendarKind.MENTORING, source, "Mentoria", null, STARTS_AT, STARTS_AT.plus(Duration.ofMinutes(50)),
			SAO_PAULO, false, now));

		// O serviço evita a duplicata, mas quem garante é o índice único: se um segundo caminho
		// gravar sem passar por ele, o banco recusa em vez de deixar a agenda mostrar duas vezes.
		assertThatThrownBy(() -> entries.save(CalendarEntry.create("cal_" + UUID.randomUUID(), owner,
				CalendarScope.PROFESSIONAL, CalendarKind.MENTORING, source, "Mentoria repetida", null, STARTS_AT,
				STARTS_AT.plus(Duration.ofMinutes(50)), SAO_PAULO, false, now)))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void twoOwnersCanHaveTheirOwnEntryForTheSameSourceId() {
		// A origem é única por dono: duas pessoas com sessões de mesmo id em agendas diferentes não
		// são o mesmo compromisso, e o índice não pode juntar as duas.
		String sourceId = "mentor_session_" + UUID.randomUUID();
		String first = owner();
		String second = owner();

		service.upsert(input(first, sourceId, STARTS_AT));
		service.upsert(input(second, sourceId, STARTS_AT));

		assertThat(entries.findBySource(first, new CalendarEntrySource("MENTORING", sourceId))).isPresent();
		assertThat(entries.findBySource(second, new CalendarEntrySource("MENTORING", sourceId))).isPresent();
	}

	@Test
	void cancellingKeepsTheRowAndAnUpsertDoesNotBringItBack() {
		String owner = owner();
		String sourceId = "mentor_session_" + UUID.randomUUID();
		CalendarEntry entry = service.upsert(input(owner, sourceId, STARTS_AT)).entry();

		assertThat(service.cancel(owner, "MENTORING", sourceId)).map(CalendarEntry::id).contains(entry.id());

		CalendarEntry cancelled = entries.findById(entry.id()).orElseThrow();
		assertThat(cancelled.active()).isFalse();
		assertThat(cancelled.cancelledAt()).isNotNull();

		// Um reagendamento que chega depois não ressuscita o compromisso cancelado.
		CalendarSourceService.UpsertResult again = service.upsert(input(owner, sourceId,
				STARTS_AT.plus(Duration.ofDays(1))));
		assertThat(again.created()).isFalse();
		assertThat(again.entry().active()).isFalse();
		assertThat(again.entry().startsAt()).isEqualTo(STARTS_AT);
	}

	@Test
	void cancellingASourceWithNoEntryIsNotAnError() {
		// O módulo dono pode cancelar uma sessão que nunca virou compromisso: nada a fazer, e não é
		// motivo para derrubar o cancelamento da sessão.
		assertThat(service.cancel(owner(), "MENTORING", "mentor_session_" + UUID.randomUUID())).isEmpty();
	}

	@Test
	void theTitleAndTheDescriptionFollowTheSource() {
		String owner = owner();
		String sourceId = "mentor_session_" + UUID.randomUUID();
		service.upsert(input(owner, sourceId, STARTS_AT));

		// O tipo do compromisso não muda, mas o texto que o módulo dono manda manda.
		CalendarSourceService.UpsertResult renamed = service.upsert(new CalendarSourceService.UpsertInput(owner,
			"MENTORING", sourceId, CalendarScope.PROFESSIONAL, CalendarKind.MENTORING, "Mentoria: Java, Spring",
			"Levar o projeto", STARTS_AT, STARTS_AT.plus(Duration.ofMinutes(50)), SAO_PAULO.getId()));

		assertThat(renamed.entry().title()).isEqualTo("Mentoria: Java, Spring");
		assertThat(renamed.entry().description()).isEqualTo("Levar o projeto");
	}

	private static String owner() {
		return "cal_owner_" + UUID.randomUUID();
	}

	private static CalendarSourceService.UpsertInput input(String owner, String sourceId, Instant startsAt) {
		return new CalendarSourceService.UpsertInput(owner, "MENTORING", sourceId, CalendarScope.PROFESSIONAL,
			CalendarKind.MENTORING, "Mentoria: Java", null, startsAt, startsAt.plus(Duration.ofMinutes(50)),
			SAO_PAULO.getId());
	}
}

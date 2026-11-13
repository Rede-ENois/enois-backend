package com.socialcoisa.rede.calendar.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.application.port.out.CalendarEntryRepository;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarEntrySource;
import com.socialcoisa.rede.calendar.domain.CalendarRecurrence;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Série de compromissos a partir de uma entrada fundadora (decisão D42).
 *
 * <p>A série é materializada em linhas irmãs, não expandida na leitura: a agenda, o conflito e o
 * `.ics` já são consultas por janela sobre `calendar_entries`, e regra escondida em tempo de leitura
 * os faria divergir. Cada irmã é um compromisso completo — aparece na agenda, ocupa conflito, leva
 * lembrete e pode ser reagendada ou cancelada sozinha, como uma ocorrência real pede.
 *
 * <p>Uma linha por série carrega a marca de ponta (`tail`). A extensão parte dela e cria as próximas
 * ocorrências até o horizonte; a marca anda para a última criada. Cancelar uma ocorrência não move a
 * marca, porque cancelar "esta" não é encerrar a série — só {@link #stop} encerra.
 *
 * <p>A idempotência é do banco (D3): o índice único (`series_id`, `starts_at`) recusa a ocorrência
 * repetida de um duplo clique, de um retry ou de duas instâncias estendendo a mesma série.
 */
@Service
public class CalendarRecurrenceService {

	private static final Logger LOG = LoggerFactory.getLogger(CalendarRecurrenceService.class);

	private final CalendarEntryRepository entries;
	private final Clock clock;
	private final boolean workerEnabled;

	public CalendarRecurrenceService(CalendarEntryRepository entries, Clock clock,
			@Value("${rede.calendar.recurrence.enabled:true}") boolean workerEnabled) {
		this.entries = entries;
		this.clock = clock;
		this.workerEnabled = workerEnabled;
	}

	/**
	 * Torna uma entrada fundadora o começo de uma série: preenche cadência nela e materializa as
	 * irmãs à frente. A fundadora mantém o que já é dela (origem vazia, título, duração); as irmãs
	 * nascem como cópias dela, sem origem.
	 */
	@Transactional
	public CalendarEntry start(String ownerId, String entryId, String recurrenceRaw) {
		CalendarEntry founder = ownEntry(ownerId, entryId);
		CalendarRecurrence recurrence = parse(recurrenceRaw);
		String seriesId = founder.seriesId() != null ? founder.seriesId() : nextSeriesId();
		Instant now = clock.instant();

		founder.joinSeries(seriesId, recurrence, now);
		entries.save(founder);
		CalendarEntry tail = tailOf(seriesId, founder);
		int created = extend(tail, now);
		LOG.info("Série {} iniciada em {}: {} ocorrências à frente", seriesId, entryId, created);
		return entries.findById(founder.id()).orElse(founder);
	}

	/**
	 * Encerra a série a partir da entrada: ela sai da série e as ocorrências futuras são canceladas.
	 * O que já aconteceu fica no histórico — cancelar o passado apagaria o que a pessoa de fato viveu.
	 * A marca de ponta some junto: série sem ponta não é estendida.
	 */
	@Transactional
	public CalendarEntry stop(String ownerId, String entryId) {
		// Encerrar a série é decisão local: ela cancela as ocorrências materializadas e não mexe no
		// fato da origem. Por isso a entrada importada de `.ics` também pode encerrar a sua série.
		CalendarEntry entry = entries.findById(entryId)
			.filter(candidate -> candidate.ownerId().equals(ownerId))
			.orElseThrow(() -> new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND", "Compromisso nao encontrado."));
		String seriesId = entry.seriesId();
		if (seriesId == null) {
			return entry;
		}
		Instant now = clock.instant();
		entry.leaveSeries(now);
		entries.save(entry);
		for (CalendarEntry sibling : entries.findBySeries(seriesId)) {
			if (sibling.seriesId() == null) {
				// A entrada que saiu da série ainda estava na lista lida antes da escrita.
				continue;
			}
			boolean future = sibling.startsAt().isAfter(now);
			sibling.closeTail();
			if (future && sibling.active()) {
				sibling.cancel(now);
			}
			entries.save(sibling);
		}
		LOG.info("Série {} encerrada em {}", seriesId, entryId);
		return entry;
	}

	/**
	 * Materializa as próximas ocorrências além da ponta `tail`. Cada ocorrência herda da anterior
	 * (título, descrição, duração, agenda, tipo, dia inteiro): editar uma ocorrência muda as que forem
	 * geradas depois dela, e é o comportamento previsível de uma série materializada sem tela de
	 * edição da série inteira.
	 *
	 * <p>Quando a ponta ficou para trás (servidor parado), o passo é repetido até o futuro sem criar o
	 * passado: o que já aconteceu está no histórico e recriá-lo duplicaria a agenda. A janela é de
	 * {@link CalendarRecurrence#HORIZON_DAYS} dias, então rodar a extensão de novo não acrescenta nada
	 * até a janela andar — é o que impede o worker de crescer a série sem limite.
	 *
	 * @return quantas ocorrências nasceram agora
	 */
	public int extend(CalendarEntry tail, Instant now) {
		CalendarRecurrence recurrence = tail.recurrence();
		if (recurrence == null || recurrence == CalendarRecurrence.NONE) {
			return 0;
		}
		String seriesId = tail.seriesId();
		int anchorDay = anchorDay(seriesId, tail);
		Instant windowEnd = now.plus(Duration.ofDays(CalendarRecurrence.HORIZON_DAYS));
		Instant cursor = firstAhead(tail, recurrence, anchorDay, now);
		CalendarEntry last = tail;
		int created = 0;
		int steps = 0;
		while (cursor != null && !cursor.isAfter(windowEnd)) {
			if (++steps > CalendarRecurrence.MAX_OCCURRENCES_PER_RUN) {
				LOG.warn("Série {} passou de {} ocorrências na janela; extensão interrompida",
					seriesId, CalendarRecurrence.MAX_OCCURRENCES_PER_RUN);
				break;
			}
			// Outra instância pode ter criado esta ocorrência: adota a que existe em vez de duplicar.
			CalendarEntry occurrence = entries.findBySeriesAndStart(seriesId, cursor).orElse(null);
			if (occurrence == null) {
				Instant start = cursor;
				occurrence = CalendarEntry.create(nextId(), last.ownerId(), last.scope(), last.kind(),
					new CalendarEntrySource(null, null), last.title(), last.description(), start,
					start.plus(last.duration()), last.zone(), last.allDay(), now);
				occurrence.joinSeriesQuietly(seriesId, recurrence);
				last.closeTail();
				entries.save(last);
				entries.save(occurrence);
				created++;
			}
			last = occurrence;
			cursor = recurrence.nextStart(cursor, tail.zone(), anchorDay);
		}
		if (last != tail) {
			last.openTail();
			entries.save(last);
		}
		return created;
	}

	/**
	 * A primeira ocorrência depois de agora. Se a ponta ficou para trás (servidor parado), o passo é
	 * repetido até passar de `now` sem criar o passado — o que já aconteceu está no histórico, e
	 * recriá-lo duplicaria a agenda. `null` quando o catch-up estoura o teto.
	 */
	private Instant firstAhead(CalendarEntry tail, CalendarRecurrence recurrence, int anchorDay, Instant now) {
		Instant cursor = tail.startsAt();
		for (int step = 0; step <= CalendarRecurrence.MAX_OCCURRENCES_PER_RUN; step++) {
			cursor = recurrence.nextStart(cursor, tail.zone(), anchorDay);
			if (cursor.isAfter(now)) {
				return cursor;
			}
		}
		LOG.warn("Série {} tem mais de {} ocorrências vencidas; não estendida neste ciclo",
			tail.seriesId(), CalendarRecurrence.MAX_OCCURRENCES_PER_RUN);
		return null;
	}

	/** A irmã mais à frente da série é a ponta; as outras perdem a marca. */
	private CalendarEntry tailOf(String seriesId, CalendarEntry founder) {
		List<CalendarEntry> siblings = entries.findBySeries(seriesId);
		CalendarEntry latest = founder;
		for (CalendarEntry sibling : siblings) {
			if (sibling.startsAt().isAfter(latest.startsAt())) {
				latest = sibling;
			}
		}
		for (CalendarEntry sibling : siblings) {
			boolean shouldBeTail = sibling.id().equals(latest.id());
			if (sibling.tail() != shouldBeTail) {
				if (shouldBeTail) {
					sibling.openTail();
				}
				else {
					sibling.closeTail();
				}
				entries.save(sibling);
			}
		}
		if (!latest.tail()) {
			latest.openTail();
			entries.save(latest);
		}
		return latest;
	}

	/**
	 * Materializa a série de uma ocorrência importada de `.ics`, cuja origem já foi gravada pelo
	 * caminho da D12 e por isso não pode ser fundadora por {@link #start} (origem presente).
	 *
	 * <p>O `RRULE` do arquivo só descreve a repetição; a série aqui é materializada como todas as
	 * outras, então cada ocorrência é um compromisso de verdade na agenda — dá para editá-la ou
	 * cancelá-la sozinha. As datas excluídas (`EXDATE`) ficam de fora: elas não existem na origem.
	 *
	 * <p>Devolve a série criada, ou vazio quando o `RRULE` não é uma cadência que este app
	 * representa (diária, por exemplo). A ocorrência original continua na agenda, única.
	 */
	@Transactional
	public Optional<String> materializeImported(String ownerId, String entryId, CalendarRecurrence recurrence,
			Set<Instant> excluded, int anchorDay, Instant until, Integer count) {
		CalendarEntry founder = entries.findById(entryId)
			.filter(candidate -> candidate.ownerId().equals(ownerId))
			.orElseThrow(() -> new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND", "Compromisso nao encontrado."));
		if (founder.seriesId() != null) {
			return Optional.empty();
		}
		String seriesId = nextSeriesId();
		Instant now = clock.instant();
		founder.joinSeriesQuietly(seriesId, recurrence);
		entries.save(founder);
		materialize(founder, seriesId, recurrence, excluded, anchorDay, until, count, now);
		return Optional.of(seriesId);
	}

	/**
	 * Cria as ocorrências da janela a partir da fundadora, pulando as datas excluídas pelo arquivo.
	 * Separado de {@link #extend} porque ali as excluídas não existem mais: a série importada é a
	 * única com buraco por desenho, e o worker não deve recriar o que o arquivo excluiu.
	 */
	private void materialize(CalendarEntry founder, String seriesId, CalendarRecurrence recurrence,
			Set<Instant> excluded, int anchorDay, Instant until, Integer count, Instant now) {
		Instant windowEnd = now.plus(Duration.ofDays(CalendarRecurrence.HORIZON_DAYS));
		// O `COUNT` do arquivo conta a ocorrência fundadora; as geradas são o resto.
		int remaining = count == null ? CalendarRecurrence.MAX_OCCURRENCES_PER_RUN : Math.max(count - 1, 0);
		Instant cursor = firstAhead(founder, recurrence, anchorDay, now);
		CalendarEntry last = founder;
		while (cursor != null && remaining > 0 && !cursor.isAfter(windowEnd)
				&& (until == null || !cursor.isAfter(until))) {
			Instant next = cursor;
			cursor = recurrence.nextStart(next, founder.zone(), anchorDay);
			remaining--;
			if (excluded.contains(next) || entries.findBySeriesAndStart(seriesId, next).isPresent()) {
				continue;
			}
			CalendarEntry occurrence = CalendarEntry.create(nextId(), founder.ownerId(), founder.scope(),
				founder.kind(), new CalendarEntrySource(null, null), founder.title(), founder.description(),
				next, next.plus(founder.duration()), founder.zone(), founder.allDay(), now);
			occurrence.joinSeriesQuietly(seriesId, recurrence);
			last.closeTail();
			entries.save(last);
			entries.save(occurrence);
			last = occurrence;
		}
		last.openTail();
		entries.save(last);
	}

	/**
	 * O worker de extensão: a cada 5 minutos, cada ponta de série viva ganha ocorrências até o
	 * horizonte. A falha de uma não derruba as outras — é o próximo ciclo que recomeça por ela.
	 */
	@Scheduled(fixedDelayString = "${rede.calendar.recurrence-delay-ms:300000}")
	public void extendDue() {
		if (!workerEnabled) {
			return;
		}
		for (CalendarEntry tail : entries.findSeriesTails()) {
			try {
				int created = extend(tail, clock.instant());
				if (created > 0) {
					LOG.info("Série {} estendida: {} novas ocorrências", tail.seriesId(), created);
				}
			}
			catch (RuntimeException error) {
				LOG.warn("Falha ao estender a série {}: {}", tail.seriesId(), error.getMessage());
			}
		}
	}

	/**
	 * O dia da âncora para a cadência mensal: o da ocorrência mais antiga da série, para o
	 * compromisso do dia 31 cair no último dia dos meses curtos e voltar ao 31 depois deles, em vez
	 * de derivar para o dia 28 para sempre.
	 */
	private int anchorDay(String seriesId, CalendarEntry fallback) {
		List<CalendarEntry> siblings = entries.findBySeries(seriesId);
		Instant earliest = null;
		int day = fallback.startsAt().atZone(fallback.zone()).getDayOfMonth();
		for (CalendarEntry sibling : siblings) {
			if (earliest == null || sibling.startsAt().isBefore(earliest)) {
				earliest = sibling.startsAt();
				day = sibling.startsAt().atZone(sibling.zone()).getDayOfMonth();
			}
		}
		return day;
	}

	private CalendarEntry ownEntry(String ownerId, String entryId) {
		CalendarEntry entry = entries.findById(entryId)
			.filter(candidate -> candidate.ownerId().equals(ownerId))
			.orElseThrow(() -> new ApiException(404, "CALENDAR_ENTRY_NOT_FOUND", "Compromisso nao encontrado."));
		if (entry.source().present()) {
			throw new ApiException(409, "CALENDAR_ENTRY_SOURCE_OWNED",
				"Nao da para mudar a recorrencia de um compromisso criado por outro recurso; "
					+ "altere no modulo de origem.");
		}
		return entry;
	}

	private static CalendarRecurrence parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(400, "INVALID_RECURRENCE", "Informe a cadência: WEEKLY, BIWEEKLY ou MONTHLY.");
		}
		String value = raw.trim();
		if (value.equals("NONE")) {
			throw new ApiException(400, "INVALID_RECURRENCE",
				"NONE nao inicia serie; use DELETE /calendar/entries/{id}/series para encerrar.");
		}
		try {
			return CalendarRecurrence.parse(value);
		}
		catch (IllegalArgumentException unknown) {
			throw new ApiException(400, "INVALID_RECURRENCE", "Cadência desconhecida: " + value + ".");
		}
	}

	private static String nextSeriesId() {
		return "cal_series_" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String nextId() {
		return "cal_" + UUID.randomUUID().toString().replace("-", "");
	}
}

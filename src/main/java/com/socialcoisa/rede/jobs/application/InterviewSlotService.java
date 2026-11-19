package com.socialcoisa.rede.jobs.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.calendar.application.CalendarAgendaItem;
import com.socialcoisa.rede.calendar.application.CalendarAvailabilityService;
import com.socialcoisa.rede.calendar.application.CalendarConflictService;
import com.socialcoisa.rede.calendar.application.CalendarWindows;
import com.socialcoisa.rede.calendar.domain.CalendarAvailabilityWindow;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.companies.domain.RecruitmentCapability;
import com.socialcoisa.rede.jobs.domain.JobInterview;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Horários livres para marcar entrevista, tirados da disponibilidade semanal que a pessoa já
 * declarou na agenda — não de um segundo calendário, nem de uma cópia do horário de trabalho.
 *
 * <p>A consulta é sempre sobre a agenda de QUEM ESTÁ MARCADO: o recrutador vê o que sobra na própria
 * agenda para encaixar a conversa. A agenda do candidato não é lida aqui — ela é privada, e o
 * convite para um horário que não serve é respondido pelo próprio candidato, não descoberto por
 * quem recruta.
 *
 * <p>Sem disponibilidade declarada a resposta é vazia com o motivo, nunca uma lista inventada de
 * horários: "não há janela" e "as janelas estão ocupadas" pedem reações diferentes de quem marca.
 */
@Service
public class InterviewSlotService {

	/** Intervalo entre começos candidatos: meia hora é o passo de quem marca reunião. */
	public static final Duration STEP = Duration.ofMinutes(30);

	/** Teto de horários devolvidos numa consulta: a tela mostra poucos e quem marca escolhe. */
	public static final int MAX_SLOTS = 50;

	private final CalendarAvailabilityService availability;
	private final CalendarConflictService conflicts;
	private final JobCommandAuthorization command;
	private final Clock clock;

	InterviewSlotService(CalendarAvailabilityService availability, CalendarConflictService conflicts,
			JobCommandAuthorization command, Clock clock) {
		this.availability = availability;
		this.conflicts = conflicts;
		this.command = command;
		this.clock = clock;
	}

	/**
	 * Os horários livres na janela pedida, em ordem.
	 *
	 * @param duration duração da entrevista; ela precisa caber inteira dentro da janela declarada e
	 *                 não pode encostar num compromisso já existente
	 */
	public Slots freeSlots(String jobId, String requesterId, Instant from, Instant to, Duration duration) {
		command.requireCommander(jobId, requesterId, RecruitmentCapability.MANAGE_INTERVIEWS,
			"So quem conduz as entrevistas da vaga vê os horários livres.");
		CalendarWindows.validate(from, to, "sugerir horários");
		Duration length = duration == null || duration.isNegative() || duration.isZero()
			? Duration.ofHours(1)
			: duration;
		if (length.compareTo(JobInterview.MAX_DURATION) > 0) {
			throw new ApiException(422, "INVALID_INTERVIEW_WINDOW",
				"A entrevista não pode durar mais de " + JobInterview.MAX_DURATION.toHours() + " horas.");
		}

		List<CalendarAvailabilityWindow> week = availability.weeklyOf(requesterId);
		if (week.isEmpty()) {
			return new Slots(List.of(), "NO_AVAILABILITY",
				"Você ainda não declarou disponibilidade na agenda; sem ela não há horário a sugerir.");
		}

		Instant now = clock.instant();
		Instant earliest = from.isAfter(now) ? from : now;
		List<Slot> slots = new ArrayList<>();
		for (CalendarAvailabilityWindow window : week) {
			collectFrom(window, earliest, to, length, requesterId, slots);
			if (slots.size() >= MAX_SLOTS) {
				break;
			}
		}
		if (slots.isEmpty()) {
			return new Slots(List.of(), "NO_FREE_SLOT",
				"Suas janelas declaradas estão ocupadas ou não alcançam a janela pedida.");
		}
		return new Slots(slots.size() > MAX_SLOTS ? slots.subList(0, MAX_SLOTS) : slots, null, null);
	}

	/**
	 * Percorre os dias da janela que caem no dia da semana daquela janela semanal.
	 *
	 * <p>A conversão é feita no fuso da janela declarada, não no UTC: "terça das 9h às 12h" é horário
	 * de parede daquela cidade, e é isso que a pessoa quis dizer.
	 */
	private void collectFrom(CalendarAvailabilityWindow window, Instant from, Instant to, Duration length,
			String requesterId, List<Slot> slots) {
		ZoneId zone = window.zone();
		ZonedDateTime cursor = from.atZone(zone).toLocalDate().atStartOfDay(zone);
		while (cursor.toInstant().isBefore(to) && slots.size() < MAX_SLOTS) {
			if (cursor.getDayOfWeek().getValue() == window.dayOfWeek()) {
				collectDay(window, cursor.toLocalDate().atStartOfDay(zone), from, to, length, requesterId, slots);
			}
			cursor = cursor.plusDays(1);
		}
	}

	private void collectDay(CalendarAvailabilityWindow window, ZonedDateTime dayStart, Instant from, Instant to,
			Duration length, String requesterId, List<Slot> slots) {
		ZoneId zone = window.zone();
		Instant windowStart = dayStart.with(window.startsAt()).toInstant();
		Instant windowEnd = dayStart.with(window.endsAt()).toInstant();
		for (Instant start = windowStart; !start.plus(length).isAfter(windowEnd)
				&& start.plus(length).isBefore(to) && slots.size() < MAX_SLOTS; start = start.plus(STEP)) {
			Instant end = start.plus(length);
			if (start.isBefore(from)
					|| conflicts.hasConflict(requesterId, start, end, CalendarScope.PROFESSIONAL, null)) {
				continue;
			}
			slots.add(new Slot(start.toString(), end.toString(), zone.getId()));
		}
	}

	/** Um horário sugerido, com o fuso em que ele faz sentido para quem marcou. */
	public record Slot(String startsAt, String endsAt, String timeZoneId) {
	}

	/**
	 * O resultado da consulta: os horários e, quando não há nenhum, o motivo — código e texto, para
	 * a tela distinguir "não declarou" de "está ocupado".
	 */
	public record Slots(List<Slot> slots, String reasonCode, String reason) {
	}

	/**
	 * O que já ocupa o horário da entrevista na agenda de QUEM MARCOU — o aviso que a tela dá para a
	 * pessoa decidir. A agenda do candidato não entra: é privada, e quem responde se o horário serve
	 * é ele pelo convite.
	 *
	 * <p>A própria entrada da entrevista não conta como conflito consigo mesma: ela é gravada na
	 * agenda de quem marca no mesmo ato de agendar.
	 */
	public List<CalendarAgendaItem> conflictsOf(String userId, JobInterview interview) {
		return conflicts.conflicts(userId, interview.startsAt(), interview.endsAt(), CalendarScope.PROFESSIONAL, null)
			.stream()
			.filter(item -> !isSameInterview(item, interview))
			.toList();
	}

	private static boolean isSameInterview(CalendarAgendaItem item, JobInterview interview) {
		return item.entry().source().present()
			&& JobInterviewService.CALENDAR_SOURCE.equals(item.entry().source().type())
			&& interview.id().equals(item.entry().source().id());
	}
}

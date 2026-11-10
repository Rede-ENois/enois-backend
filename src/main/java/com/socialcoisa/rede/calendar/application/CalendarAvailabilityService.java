package com.socialcoisa.rede.calendar.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.calendar.application.port.out.CalendarAvailabilityRepository;
import com.socialcoisa.rede.calendar.domain.CalendarAvailabilityWindow;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Disponibilidade semanal da agenda (fase 6, decisão D24).
 *
 * <p>A semana inteira é substituída de uma vez: o cliente manda o conjunto que quer e o que não
 * veio deixa de existir. Isso evita uma rota de "apagar janela por id" e o estado em que a tela
 * acha que apagou e o banco não — a regra é o conjunto, não a linha.
 *
 * <p>Duas janelas que se sobrepõem no mesmo dia são recusadas: não são duas disponibilidades, são
 * a mesma com a borda torta. Encostar uma na outra (9h–12h e 12h–18h) é permitido, e é o jeito de
 * declarar o dia inteiro.
 */
@Service
public class CalendarAvailabilityService {

	/** Teto de janelas por pessoa: uma semana com mais que isso é erro de cliente, não agenda. */
	public static final int MAX_WINDOWS = 50;

	private static final Comparator<CalendarAvailabilityWindow> ORDER = Comparator
		.comparingInt(CalendarAvailabilityWindow::dayOfWeek)
		.thenComparing(CalendarAvailabilityWindow::startsAt);

	private final CalendarAvailabilityRepository windows;
	private final Clock clock;

	CalendarAvailabilityService(CalendarAvailabilityRepository windows, Clock clock) {
		this.windows = windows;
		this.clock = clock;
	}

	/** A semana de quem está lendo, ordenada por dia e começo. */
	public List<CalendarAvailabilityWindow> weeklyOf(String userId) {
		return windows.findByUser(userId).stream().sorted(ORDER).toList();
	}

	/**
	 * Substitui a semana. As janelas entram validadas e ordenadas; se qualquer uma for inválida ou
	 * cruzar outra, nada é gravado — a semana anterior continua inteira em vez de ficar pela metade.
	 */
	@Transactional
	public List<CalendarAvailabilityWindow> replaceWeekly(String userId, List<WeeklyWindow> requested) {
		if (requested == null) {
			throw invalid("Informe as janelas da semana.");
		}
		if (requested.size() > MAX_WINDOWS) {
			throw invalid("Uma semana comporta no máximo " + MAX_WINDOWS + " janelas.");
		}
		Instant now = clock.instant();
		List<CalendarAvailabilityWindow> built = new ArrayList<>();
		for (WeeklyWindow wanted : requested) {
			if (wanted == null) {
				throw invalid("Janela da semana não pode ser nula.");
			}
			built.add(create(userId, wanted, now));
		}
		built.sort(ORDER);
		rejectOverlaps(built);
		windows.replaceFor(userId, built);
		return built;
	}

	private static CalendarAvailabilityWindow create(String userId, WeeklyWindow wanted, Instant now) {
		if (wanted.dayOfWeek() == null) {
			throw invalid("Informe o dia da semana de cada janela.");
		}
		if (wanted.startsAt() == null || wanted.endsAt() == null) {
			throw invalid("Informe o começo e o fim de cada janela.");
		}
		if (wanted.zone() == null) {
			throw invalid("Informe o fuso de cada janela.");
		}
		try {
			return CalendarAvailabilityWindow.create(
				"ava_" + UUID.randomUUID().toString().replace("-", ""), userId, wanted.dayOfWeek(),
				wanted.startsAt(), wanted.endsAt(), wanted.zone(), now);
		}
		catch (IllegalArgumentException error) {
			throw invalid(error.getMessage());
		}
	}

	private static void rejectOverlaps(List<CalendarAvailabilityWindow> ordered) {
		for (int i = 0; i < ordered.size(); i++) {
			for (int j = i + 1; j < ordered.size(); j++) {
				if (ordered.get(i).overlaps(ordered.get(j))) {
					throw invalid("Duas janelas no mesmo dia se sobrepõem; junte as duas em uma.");
				}
			}
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(400, "INVALID_AVAILABILITY", message);
	}

	/**
	 * O que a rota recebe já convertido: horários e fuso são valores de calendário, não texto. A
	 * conversão fica no adaptador web, que também é quem devolve 400 para texto malformado.
	 */
	public record WeeklyWindow(Integer dayOfWeek, LocalTime startsAt, LocalTime endsAt, ZoneId zone) {
	}

}
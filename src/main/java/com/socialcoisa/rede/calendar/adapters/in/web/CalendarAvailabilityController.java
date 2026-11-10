package com.socialcoisa.rede.calendar.adapters.in.web;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calendar.application.CalendarAvailabilityService;
import com.socialcoisa.rede.calendar.domain.CalendarAvailabilityWindow;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Disponibilidade semanal da agenda de quem está lendo.
 *
 * <p>A escrita é uma substituição: o corpo traz a semana que a pessoa quer e o que não veio deixa
 * de existir. Não há rota para outra pessoa — a disponibilidade é sempre lida e escrita a partir da
 * sessão, e não existe id de pessoa no caminho (mesma regra de D10/D13).
 *
 * <p>Os horários entram e saem como `hh:mm` de parede no fuso declarado, não como instante: a regra
 * é semanal e não pode mudar quando o relógio da cidade muda.
 */
@RestController
@RequestMapping("/calendar/availability")
class CalendarAvailabilityController {

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	private final CurrentUserResolver currentUserResolver;
	private final CalendarAvailabilityService availability;

	CalendarAvailabilityController(CurrentUserResolver currentUserResolver,
			CalendarAvailabilityService availability) {
		this.currentUserResolver = currentUserResolver;
		this.availability = availability;
	}

	@GetMapping
	List<WindowView> weekly(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return availability.weeklyOf(user.id().value()).stream().map(WindowView::from).toList();
	}

	@PutMapping
	List<WindowView> replace(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) AvailabilityRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null || request.windows() == null) {
			throw new ApiException(400, "INVALID_AVAILABILITY", "Informe as janelas da semana.");
		}
		List<CalendarAvailabilityService.WeeklyWindow> windows = request.windows().stream()
			.map(CalendarAvailabilityController::windowOf)
			.toList();
		return availability.replaceWeekly(user.id().value(), windows).stream().map(WindowView::from).toList();
	}

	private static CalendarAvailabilityService.WeeklyWindow windowOf(WindowRequest request) {
		if (request == null) {
			throw invalid("Janela da semana não pode ser nula.");
		}
		return new CalendarAvailabilityService.WeeklyWindow(request.dayOfWeek(), time(request.startsAt()),
			time(request.endsAt()), zone(request.timezone()));
	}

	private static LocalTime time(String value) {
		if (value == null || value.isBlank()) {
			throw invalid("Informe o horário no formato hh:mm.");
		}
		try {
			return LocalTime.parse(value.trim(), CLOCK);
		}
		catch (DateTimeParseException error) {
			throw invalid("Horário inválido: use hh:mm.");
		}
	}

	private static ZoneId zone(String value) {
		if (value == null || value.isBlank()) {
			throw invalid("Informe o fuso da janela.");
		}
		try {
			return ZoneId.of(value.trim());
		}
		catch (DateTimeException error) {
			throw invalid("Fuso inválido: use o identificador IANA, como America/Sao_Paulo.");
		}
	}

	private static ApiException invalid(String message) {
		return new ApiException(400, "INVALID_AVAILABILITY", message);
	}

	record WindowRequest(Integer dayOfWeek, String startsAt, String endsAt, String timezone) {
	}

	record AvailabilityRequest(List<WindowRequest> windows) {
	}

	record WindowView(String id, int dayOfWeek, String startsAt, String endsAt, String timezone) {

		static WindowView from(CalendarAvailabilityWindow window) {
			return new WindowView(window.id(), window.dayOfWeek(), window.startsAt().format(CLOCK),
				window.endsAt().format(CLOCK), window.zone().getId());
		}
	}

}
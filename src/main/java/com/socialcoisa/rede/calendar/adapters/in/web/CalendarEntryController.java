package com.socialcoisa.rede.calendar.adapters.in.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calendar.application.CalendarAgendaItem;
import com.socialcoisa.rede.calendar.application.CalendarCommandService;
import com.socialcoisa.rede.calendar.application.CalendarConflictService;
import com.socialcoisa.rede.calendar.application.CalendarInvitationService;
import com.socialcoisa.rede.calendar.application.CalendarQueryService;
import com.socialcoisa.rede.calendar.application.CalendarReminderService;
import com.socialcoisa.rede.calendar.domain.CalendarEntry;
import com.socialcoisa.rede.calendar.domain.CalendarParticipant;
import com.socialcoisa.rede.calendar.domain.CalendarReminder;
import com.socialcoisa.rede.calendar.domain.CalendarScope;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Agenda do próprio usuário: leitura e escrita. Não existe rota pública, sem sessão a resposta é
 * 401, e o identificador de outra pessoa não é distinguível de um que não existe.
 *
 * <p>Toda escrita exige `Idempotency-Key`, e a rota entra na chave do registro: a mesma chave
 * repetida devolve a resposta guardada em vez de criar ou cancelar duas vezes.
 *
 * <p>Os instantes entram e saem em ISO-8601 UTC. O fuso do organizador viaja junto na entrada, para
 * a tela mostrar "às 19h" mesmo quando a leitura acontece em outro fuso.
 */
@RestController
@RequestMapping("/calendar/entries")
class CalendarEntryController {

	private final CurrentUserResolver currentUserResolver;
	private final CalendarQueryService calendarQueryService;
	private final CalendarCommandService calendarCommandService;
	private final CalendarInvitationService calendarInvitationService;
	private final UserProfileRepository profiles;
	private final IdempotencyApplicationService idempotency;
	private final CalendarReminderService calendarReminderService;
	private final CalendarConflictService calendarConflictService;

	CalendarEntryController(CurrentUserResolver currentUserResolver, CalendarQueryService calendarQueryService,
			CalendarCommandService calendarCommandService, CalendarInvitationService calendarInvitationService,
			UserProfileRepository profiles, IdempotencyApplicationService idempotency,
			CalendarReminderService calendarReminderService, CalendarConflictService calendarConflictService) {
		this.currentUserResolver = currentUserResolver;
		this.calendarQueryService = calendarQueryService;
		this.calendarCommandService = calendarCommandService;
		this.calendarInvitationService = calendarInvitationService;
		this.profiles = profiles;
		this.idempotency = idempotency;
		this.calendarReminderService = calendarReminderService;
		this.calendarConflictService = calendarConflictService;
	}

	/**
	 * Agenda de quem está lendo: o que organiza mais o que foi convidada a participar, cada item com
	 * o lugar dela. Sem `includeInvited=false` não existe filtro de "só o que eu organizo": a tela
	 * filtra pelo campo `owner` do item, e o backend devolve a agenda inteira da pessoa.
	 */
	@GetMapping
	List<AgendaItemView> entries(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String scope) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return calendarQueryService.agenda(user.id().value(), instant(from, "from"), instant(to, "to"),
			scope(scope)).stream().map(AgendaItemView::from).toList();
	}

	/**
	 * O que já ocupa o intervalo que a pessoa está tentando usar. É leitura, não bloqueio: a agenda
	 * permite sobreposição de propósito, e a tela usa a lista para avisar antes de salvar. A consulta
	 * é sempre a agenda de quem está lendo — nunca a de outra pessoa.
	 *
	 * <p>`entryId` sai da lista para editar um compromisso existente não acusar conflito com ele
	 * mesmo.
	 */
	@GetMapping("/conflicts")
	List<AgendaItemView> conflicts(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String scope,
			@RequestParam(required = false) String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return calendarConflictService.conflicts(user.id().value(), instant(from, "from"), instant(to, "to"),
			scope(scope), entryId).stream().map(AgendaItemView::from).toList();
	}

	/**
	 * Detalhe com o lugar de quem lê. O organizador recebe a lista de participantes; o convidado
	 * recebe o mesmo compromisso com a própria resposta e a lista vem nula, porque quem foi
	 * convidado não precisa ver os outros convidados da agenda de alguém.
	 */
	@GetMapping("/{entryId}")
	AgendaDetailView entry(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		CalendarAgendaItem item = calendarQueryService.detail(user.id().value(), entryId);
		List<ParticipantView> participants = item.owner()
			? views(calendarInvitationService.participants(user.id().value(), entryId))
			: null;
		return AgendaDetailView.from(item, participants);
	}

	/**
	 * Criação. A linha nasce sem origem: quem grava entrada de outro módulo (evento, mentoria) usa o
	 * serviço de aplicação, com a origem preenchida, e não esta rota.
	 */
	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) CalendarEntryRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY", "Informe os dados do compromisso.");
		}
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id().value(), "POST", "/api/calendar/entries", idempotencyKey, hash,
			() -> {
				CalendarEntry entry = calendarCommandService.create(user.id().value(), request.scope(),
					request.kind(), request.title(), request.description(), request.startsAt(), request.endsAt(),
					request.timeZoneId(), request.allDay());
				return new CommandResponse(201, CalendarEntryView.from(entry));
			});
	}

	@PatchMapping("/{entryId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String entryId, @RequestBody(required = false) CalendarEntryPatchRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null) {
			throw new ApiException(400, "INVALID_CALENDAR_ENTRY", "Informe os campos a alterar.");
		}
		String hash = idempotency.hash(Map.of("entryId", entryId, "request", request));
		return idempotency.execute(user.id().value(), "PATCH", "/api/calendar/entries/{entryId}", idempotencyKey,
			hash, () -> {
				CalendarEntry entry = calendarCommandService.update(user.id().value(), entryId, request.title(),
					request.description(), request.startsAt(), request.endsAt(), request.timeZoneId());
				return new CommandResponse(200, CalendarEntryView.from(entry));
			});
	}

	/** Cancela, não apaga: o `.ics` publica `STATUS:CANCELLED` com o mesmo `UID`. */
	@DeleteMapping("/{entryId}")
	ResponseEntity<String> cancel(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("entryId", entryId));
		return idempotency.execute(user.id().value(), "DELETE", "/api/calendar/entries/{entryId}", idempotencyKey,
			hash, () -> new CommandResponse(200,
				CalendarEntryView.from(calendarCommandService.cancel(user.id().value(), entryId))));
	}

	/** Convida pelo @. Só o organizador; a resposta é de quem foi convidado, em outra rota. */
	@PostMapping("/{entryId}/participants")
	ResponseEntity<String> invite(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String entryId, @RequestBody(required = false) ParticipantRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null) {
			throw new ApiException(400, "INVITE_USERNAME_REQUIRED", "Informe o @ de quem convidar.");
		}
		String hash = idempotency.hash(Map.of("entryId", entryId, "request", request));
		return idempotency.execute(user.id().value(), "POST", "/api/calendar/entries/{entryId}/participants",
			idempotencyKey, hash, () -> new CommandResponse(201,
				views(List.of(calendarInvitationService.invite(user.id().value(), entryId,
					request.username()))).get(0)));
	}

	@GetMapping("/{entryId}/participants")
	List<ParticipantView> participants(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return views(calendarInvitationService.participants(user.id().value(), entryId));
	}

	/**
	 * Resposta do convidado. A pessoa é tirada da sessão, nunca do caminho: assim ninguém responde
	 * convite alheio nem precisa mandar o próprio id.
	 */
	@PatchMapping("/{entryId}/participants/me")
	ResponseEntity<String> respond(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String entryId, @RequestBody(required = false) ResponseRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (request == null) {
			throw new ApiException(400, "INVALID_INVITE_RESPONSE",
				"Informe a resposta: ACCEPTED, DECLINED ou TENTATIVE.");
		}
		String hash = idempotency.hash(Map.of("entryId", entryId, "request", request));
		return idempotency.execute(user.id().value(), "PATCH",
			"/api/calendar/entries/{entryId}/participants/me", idempotencyKey, hash,
			() -> new CommandResponse(200, views(List.of(
				calendarInvitationService.respond(user.id().value(), entryId, request.state()))).get(0)));
	}

	/**
	 * Lembretes do compromisso para quem está lendo. A pessoa vem da sessão, nunca do caminho, e o
	 * compromisso de outra pessoa responde 404 igual a um que não existe (D10).
	 */
	@GetMapping("/{entryId}/reminders")
	List<ReminderView> reminders(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String entryId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return calendarReminderService.ofReader(user.id().value(), entryId).stream()
			.map(ReminderView::from)
			.toList();
	}

	/**
	 * Põe o lembrete que a pessoa escolheu. Idempotente: repetir a mesma antecedência devolve a
	 * linha que já existe, porque o botão pode ter sido apertado duas vezes.
	 */
	@PutMapping("/{entryId}/reminders")
	ReminderView setReminder(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String entryId, @RequestParam(required = false) Integer minutesBefore) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (minutesBefore == null) {
			throw new ApiException(400, "INVALID_REMINDER", "Informe minutesBefore (5, 15, 30, 60 ou 1440).");
		}
		try {
			return ReminderView.from(
				calendarReminderService.setForReader(user.id().value(), entryId, minutesBefore));
		}
		catch (IllegalArgumentException error) {
			throw new ApiException(400, "INVALID_REMINDER", error.getMessage());
		}
	}

	@DeleteMapping("/{entryId}/reminders")
	void clearReminder(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String entryId, @RequestParam(required = false) Integer minutesBefore) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		if (minutesBefore != null) {
			try {
				calendarReminderService.clearForReader(user.id().value(), entryId, minutesBefore);
			}
			catch (IllegalArgumentException error) {
				throw new ApiException(400, "INVALID_REMINDER", error.getMessage());
			}
		}
	}

	record ReminderView(String id, String entryId, int minutesBefore, String dueAt, boolean sent) {

		static ReminderView from(CalendarReminder reminder) {
			return new ReminderView(reminder.id(), reminder.entryId(), reminder.minutesBefore(),
				reminder.dueAt().toString(), reminder.sentAt() != null);
		}
	}

	@DeleteMapping("/{entryId}/participants/{participantId}")
	ResponseEntity<Void> removeParticipant(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String entryId, @PathVariable String participantId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		calendarInvitationService.remove(user.id().value(), entryId, participantId);
		return ResponseEntity.noContent().build();
	}

	/** O @ vem do perfil, em uma consulta só; a tela mostra @ e nunca o id da pessoa. */
	private List<ParticipantView> views(List<CalendarParticipant> participants) {
		List<String> userIds = participants.stream().map(CalendarParticipant::userId).distinct().toList();
		Map<String, String> usernames = new java.util.HashMap<>();
		for (var profile : profiles.findAllByIds(userIds)) {
			usernames.put(profile.id(), profile.username());
		}
		return participants.stream()
			.map(participant -> ParticipantView.from(participant, usernames.get(participant.userId())))
			.toList();
	}

	private static Instant instant(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(400, "INVALID_WINDOW", "Informe " + field + " em ISO-8601.");
		}
		try {
			return Instant.parse(raw.trim());
		} catch (DateTimeParseException exception) {
			throw new ApiException(400, "INVALID_WINDOW", field + " precisa ser um instante ISO-8601.");
		}
	}

	private static CalendarScope scope(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return CalendarScope.parse(raw.trim());
		} catch (IllegalArgumentException exception) {
			throw new ApiException(400, "INVALID_SCOPE", "Agenda invalida: use SOCIAL ou PROFESSIONAL.");
		}
	}

	/** O contrato de saída não expõe a entidade nem o dono: quem lê já é o dono. */
	record CalendarEntryView(String id, String scope, String kind, String title, String description,
			String startsAt, String endsAt, String timeZoneId, boolean allDay, String state,
			String sourceType, String sourceId, String createdAt, String updatedAt, String cancelledAt) {

		static CalendarEntryView from(CalendarEntry entry) {
			return new CalendarEntryView(entry.id(), entry.scope().name(), entry.kind().name(), entry.title(),
				entry.description(), entry.startsAt().toString(), entry.endsAt().toString(), entry.zone().getId(),
				entry.allDay(), entry.state().name(), entry.source().type(), entry.source().id(),
				entry.createdAt().toString(), entry.updatedAt().toString(),
				entry.cancelledAt() == null ? null : entry.cancelledAt().toString());
		}
	}

	/** Item da agenda: o compromisso mais o lugar de quem está lendo nele. */
	record AgendaItemView(CalendarEntryView entry, boolean owner, String participation) {

		static AgendaItemView from(CalendarAgendaItem item) {
			return new AgendaItemView(CalendarEntryView.from(item.entry()), item.owner(), participationOf(item));
		}
	}

	/** Detalhe: o item da agenda mais os participantes quando quem lê é o organizador. */
	record AgendaDetailView(CalendarEntryView entry, boolean owner, String participation,
			List<ParticipantView> participants) {

		static AgendaDetailView from(CalendarAgendaItem item, List<ParticipantView> participants) {
			return new AgendaDetailView(CalendarEntryView.from(item.entry()), item.owner(),
				participationOf(item), participants);
		}
	}

	private static String participationOf(CalendarAgendaItem item) {
		return item.participation() == null ? null : item.participation().name();
	}

	/**
	 * O convite como o organizador o vê. `userId` fica de fora: a tela mostra @, não id, e o
	 * respondente é tirado da sessão em `/participants/me`.
	 */
	record ParticipantView(String id, String entryId, String username, String state, String invitedAt,
			String respondedAt) {

		static ParticipantView from(CalendarParticipant participant, String username) {
			return new ParticipantView(participant.id(), participant.entryId(), username,
				participant.state().name(), participant.invitedAt().toString(),
				participant.respondedAt() == null ? null : participant.respondedAt().toString());
		}
	}

	record ParticipantRequest(String username) {
	}

	record ResponseRequest(String state) {
	}

	/** Corpo da criação: `scope` e `kind` têm padrão (SOCIAL e PERSONAL) para o app não mandar sempre. */
	record CalendarEntryRequest(String scope, String kind, String title, String description, String startsAt,
			String endsAt, String timeZoneId, boolean allDay) {
	}

	/** Corpo da edição parcial: campo nulo ou ausente não muda; `description` vazia limpa o texto. */
	record CalendarEntryPatchRequest(String title, String description, String startsAt, String endsAt,
			String timeZoneId) {
	}
}

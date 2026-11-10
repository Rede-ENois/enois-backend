package com.socialcoisa.rede.calendar.adapters.in.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calendar.application.CalendarInvitationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Convites que ainda esperam resposta, para a tela mostrar "você foi convidado" sem varrer a
 * agenda inteira. Só o que a pessoa ainda não respondeu: aceito e recusado já aparecem na agenda
 * dela, com o estado.
 */
@RestController
@RequestMapping("/calendar/invitations")
class CalendarInvitationsController {

	private final CurrentUserResolver currentUserResolver;
	private final CalendarInvitationService calendarInvitationService;

	CalendarInvitationsController(CurrentUserResolver currentUserResolver,
			CalendarInvitationService calendarInvitationService) {
		this.currentUserResolver = currentUserResolver;
		this.calendarInvitationService = calendarInvitationService;
	}

	@GetMapping
	List<CalendarEntryController.CalendarEntryView> pending(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return calendarInvitationService.pendingInvitations(user.id().value()).stream()
			.map(CalendarEntryController.CalendarEntryView::from)
			.toList();
	}

}

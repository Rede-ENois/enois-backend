package com.socialcoisa.rede.jobs.adapters.in.web;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.jobs.application.InterviewSlotService;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Horários livres para marcar entrevista na vaga, a partir da disponibilidade semanal de quem
 * conduz as entrevistas.
 *
 * <p>Mesma superfície de {@link JobContractController} (`/jobs/{jobId}/...`), arquivo próprio para
 * não engrossar o controller de vagas. A agenda consultada é sempre a de QUEM ESTÁ MARCADO (a
 * sessão): a do candidato é privada e não é lida aqui.
 */
@RestController
@RequestMapping("/jobs/{jobId}/interviews")
class InterviewSlotController {

	private final InterviewSlotService slots;
	private final CurrentUserResolver currentUserResolver;

	InterviewSlotController(InterviewSlotService slots, CurrentUserResolver currentUserResolver) {
		this.slots = slots;
		this.currentUserResolver = currentUserResolver;
	}

	/**
	 * Os horários livres na janela pedida. `from` e `to` em ISO-8601 UTC; `durationMinutes` é a
	 * duração pretendida da entrevista (padrão 60).
	 */
	@GetMapping("/free-slots")
	SlotListView freeSlots(@PathVariable String jobId,
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) Integer durationMinutes,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Duration duration = durationMinutes == null ? Duration.ofHours(1) : Duration.ofMinutes(durationMinutes);
		InterviewSlotService.Slots result = slots.freeSlots(jobId, user.id().value(),
			parseInstant(from, "from"), parseInstant(to, "to"), duration);
		return SlotListView.of(result);
	}

	/** Instante ISO-8601; ausente ou inválido é erro do cliente (422), não erro interno. */
	private static Instant parseInstant(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(422, "INVALID_WINDOW", "Informe " + field + " da consulta.");
		}
		try {
			return Instant.parse(raw.trim());
		}
		catch (DateTimeParseException exception) {
			throw new ApiException(422, "INVALID_WINDOW", "Data/hora inválida em " + field + ": " + raw);
		}
	}

	/** Os horários e, quando não há nenhum, o motivo — código e texto, não lista vazia muda. */
	record SlotListView(List<SlotView> slots, String reasonCode, String reason) {

		static SlotListView of(InterviewSlotService.Slots result) {
			return new SlotListView(result.slots().stream().map(SlotView::of).toList(), result.reasonCode(),
				result.reason());
		}
	}

	record SlotView(String startsAt, String endsAt, String timeZoneId) {

		static SlotView of(InterviewSlotService.Slot slot) {
			return new SlotView(slot.startsAt(), slot.endsAt(), slot.timeZoneId());
		}
	}
}

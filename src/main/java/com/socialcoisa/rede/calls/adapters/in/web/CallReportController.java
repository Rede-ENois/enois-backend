package com.socialcoisa.rede.calls.adapters.in.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.calls.application.CallReportService;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Denúncia de chamada: quem dá para denunciar nesta sala e o envio da denúncia com a captura.
 *
 * <p>Tudo exige sessão e o vínculo com a chamada é conferido no servidor. O corpo do pedido diz
 * QUEM foi denunciado, o motivo e o relato; quem estava na sala e a captura anexada são conferidos
 * e montados do lado de cá.</p>
 */
@RestController
class CallReportController {

	private final CallReportService reports;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	CallReportController(CallReportService reports, CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency) {
		this.reports = reports;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	/** Quem pode ser denunciado nesta chamada (as outras pessoas que entraram na sala). */
	@GetMapping("/calls/{callId}/reportable")
	List<CallReportService.ParticipantView> reportable(@RequestHeader("X-Session-Token") String sessionToken,
			@PathVariable String callId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return reports.reportable(callId, user.id().value());
	}

	/**
	 * Denuncia um participante da chamada. {@code mediaId} é a captura do momento, já enviada por
	 * quem denuncia (opcional: sem ela a denúncia vale, com o relato).
	 */
	@PostMapping("/calls/{callId}/reports")
	ResponseEntity<String> report(@RequestHeader("X-Session-Token") String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String callId, @RequestBody CreateCallReportRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/calls/{callId}/reports", idempotencyKey, hash,
			() -> {
				var report = reports.report(callId, user.id().value(), request.reportedUserId(), request.reason(),
					request.description(), request.mediaId());
				return new CommandResponse(201, new CallReportView(report.id(), report.status().name()));
			});
	}

	record CreateCallReportRequest(String reportedUserId, ReportReason reason, String description, String mediaId) {
	}

	record CallReportView(String id, String status) {
	}
}

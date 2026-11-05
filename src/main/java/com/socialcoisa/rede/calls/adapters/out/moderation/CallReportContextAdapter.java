package com.socialcoisa.rede.calls.adapters.out.moderation;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.calls.application.port.out.CallReportRepository;
import com.socialcoisa.rede.calls.domain.CallReportContext;
import com.socialcoisa.rede.moderation.application.port.out.CallReportContextRepository;

/**
 * Deixa a moderação ler o contexto da denúncia de chamada sem conhecer as tabelas de chamada.
 *
 * <p>Quem grava é o módulo de chamadas (é ele que sabe quem estava na sala); a moderação só
 * consulta, pelo id da denúncia.</p>
 */
@Component
class CallReportContextAdapter implements CallReportContextRepository {

	private final CallReportRepository reports;

	CallReportContextAdapter(CallReportRepository reports) {
		this.reports = reports;
	}

	@Override
	public Optional<CallReportContext> findByReportId(String reportId) {
		return reports.findByReportId(reportId);
	}

	@Override
	public boolean alreadyReported(String reporterId, String callId, String reportedUserId) {
		return reports.exists(reporterId, callId, reportedUserId);
	}
}

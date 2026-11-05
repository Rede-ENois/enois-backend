package com.socialcoisa.rede.calls.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.calls.domain.CallReportContext;

/** Contexto das denúncias de chamada: quem foi denunciado, quem estava dentro e o print do momento. */
public interface CallReportRepository {

	void save(CallReportContext context);

	Optional<CallReportContext> findByReportId(String reportId);

	/** Existe denúncia deste denunciante contra este participante desta chamada? */
	boolean exists(String reporterId, String callId, String reportedUserId);
}

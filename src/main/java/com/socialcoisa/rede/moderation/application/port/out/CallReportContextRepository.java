package com.socialcoisa.rede.moderation.application.port.out;

import java.util.Optional;

import com.socialcoisa.rede.calls.domain.CallReportContext;

/**
 * Contexto da denúncia de chamada (quem foi denunciado, quem estava na sala, print do momento).
 *
 * <p>O módulo de moderação lê por aqui sem saber das tabelas de chamada; quem grava é o módulo de
 * chamadas, que é o dono do dado.</p>
 */
public interface CallReportContextRepository {

	Optional<CallReportContext> findByReportId(String reportId);

	/**
	 * A pessoa já denunciou este participante desta chamada? A denúncia de chamada é por
	 * DENUNCIADO: duas pessoas diferentes na mesma sala são dois casos, a mesma pessoa duas vezes
	 * não é.
	 */
	boolean alreadyReported(String reporterId, String callId, String reportedUserId);
}

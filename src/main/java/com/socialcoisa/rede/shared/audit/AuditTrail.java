package com.socialcoisa.rede.shared.audit;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.domain.UserId;

/**
 * Atalho para gravar auditoria com o id do ator em texto.
 *
 * <p>O {@link AuditLogRepository} fala em {@link UserId}; a rede administra nós, e o ator de uma ação
 * administrativa é sempre uma conta identificada pelo id. Esta ponte evita cada serviço repetir o
 * {@code new UserId(actorId)} e deixa claro que auditoria de rede usa a MESMA trilha das demais
 * ações administrativas — não uma tabela nova.</p>
 */
@Component
public class AuditTrail {

	private final AuditLogRepository repository;

	AuditTrail(AuditLogRepository repository) {
		this.repository = repository;
	}

	public void record(String actorId, String action, String targetId, String metadataJson) {
		repository.record(new UserId(actorId), action, targetId, metadataJson);
	}
}

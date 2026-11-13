package com.socialcoisa.rede.feed.application.port.out;

import java.time.Instant;
import java.util.List;

import com.socialcoisa.rede.feed.domain.UserId;

public interface AuditLogRepository {

	void record(UserId actorId, String action, String targetId, String metadata);

	/**
	 * As ações registradas contra um alvo, mais novas primeiro.
	 *
	 * <p>É a leitura do painel ("histórico do nó"), não uma consulta genérica de auditoria: o alvo
	 * é sempre um id estável e a leitura é limitada. Vai pelo índice {@code (target_id, created_at)}
	 * da V286 — sem ele, cada abertura de página varreria a maior tabela de trilha do sistema.</p>
	 */
	List<AuditRecord> recentForTarget(String targetId, int limit);

	/** Uma linha da trilha como a tela a mostra; {@code metadata} é JSON opaco para quem lê. */
	record AuditRecord(String id, String actorId, String action, String targetId, String metadata,
			Instant createdAt) {
	}
}

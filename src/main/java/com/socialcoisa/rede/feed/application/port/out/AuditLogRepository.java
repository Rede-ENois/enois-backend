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
	 * da V315 — sem ele, cada abertura de página varreria a maior tabela de trilha do sistema.</p>
	 */
	List<AuditRecord> recentForTarget(String targetId, int limit);

	/** Uma linha da trilha como a tela a mostra; {@code metadata} é JSON opaco para quem lê. */
	record AuditRecord(String id, String actorId, String action, String targetId, String metadata,
			Instant createdAt) {
	}

	/**
	 * Quantas linhas deste ator com a ação dada existem — a contribuição contada na própria trilha.
	 *
	 * <p>Serve à tela do participante: a recusa de um resultado é auditada com o nó como ator, e
	 * essa linha é a única prova de que ele <b>entregou</b> e devolveu à fila. Contar da mesmíssima
	 * linha que prova o fato impede que o número e a prova divirjam.</p>
	 *
	 * <p>Vai pelo índice {@code idx_audit_actor_created} (V1): a leitura é limitada ao ator, que é
	 * um id de nó, e não à maior tabela de trilha do sistema.</p>
	 */
	long countByActorAndAction(UserId actorId, String action);
}

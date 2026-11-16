package com.socialcoisa.rede.feed.application.port.in;

/**
 * Remocao de post por moderacao, sem exigir que quem remove seja o autor.
 */
public interface ModerationRemovePostUseCase {

	void removeAsModerator(String moderatorId, String postId, String reason);

	/**
	 * O mesmo, mas DIZ o que aconteceu.
	 *
	 * <p>O método void acima é silencioso: quando o post não existe (ou já foi removido), ele
	 * simplesmente volta, e quem chamou recebe a mesma resposta de quem removiu. Num painel de
	 * moderação isso vira "deu certo" onde nada aconteceu — e é o que produz a mensagem
	 * "Operacao conflita com o estado atual dos dados" sem dizer o que houve.</p>
	 */
	RemovalOutcome removeAsModeratorWithOutcome(String moderatorId, String postId, String reason);

	/**
	 * A decisão DIRETA do administrador.
	 *
	 * <p>O efeito é o mesmo do caminho de moderação (soft delete, cache, mídia, republicações,
	 * auditoria {@code POST_DELETED_BY_MODERATION}), inclusive para post em quarentena — que é o
	 * desfecho de "violação confirmada". A porta existe separada para o painel administrativo não
	 * depender da regra de quórum e para o contrato de resposta ({@code REMOVED},
	 * {@code NOT_FOUND}, {@code ALREADY_OFFLINE}) ficar explícito no nome de quem decidiu.</p>
	 */
	RemovalOutcome removeAsAdminWithOutcome(String adminId, String postId, String reason);

	/** O que a remoção fez com o post. */
	enum RemovalOutcome {
		/** O post estava no ar e saiu. */
		REMOVED,
		/** O post não existe. */
		NOT_FOUND,
		/** O post já estava fora do ar (removido, expirado ou em quarentena já tratada). */
		ALREADY_OFFLINE
	}
}

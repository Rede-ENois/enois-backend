package com.socialcoisa.rede.moderation.domain;

import java.time.Instant;

/**
 * Uma linha do livro-caixa de conteúdo automatizado: o que foi avaliado, com que veredito e em que
 * pé está a revisão.
 *
 * <p>Existe para não avaliar o mesmo post duas vezes (a varredura relê a janela recente a cada
 * rodada) e para o autor conseguir entender depois por que a publicação ficou fora do ar.</p>
 */
public record ContentAutomationCheck(String postId, String authorId, ContentAutomationVerdict verdict,
		double confidence, String reason, String model, Instant checkedAt, ReviewStatus status) {

	/** Em que pé está a revisão humana. */
	public enum ReviewStatus {
		/**
		 * Nada a revisar: o post nunca saiu do ar (veredito humano, ou suspeita sem confiança
		 * suficiente). É o padrão do registro e o que mantém essas linhas fora da fila do moderador.
		 */
		NONE,
		/** Post em quarentena, esperando uma pessoa. */
		PENDING,
		/** Revisão liberou: o post voltou ao ar. */
		RELEASED,
		/** Revisão confirmou a violação: o post foi removido. */
		REMOVED
	}

	/** Avaliação que NÃO tirou o post do ar: nada a revisar. */
	public static ContentAutomationCheck of(String postId, String authorId, ContentAutomationVerdict verdict,
			double confidence, String reason, String model, Instant checkedAt) {
		return of(postId, authorId, verdict, confidence, reason, model, checkedAt, ReviewStatus.NONE);
	}

	public static ContentAutomationCheck of(String postId, String authorId, ContentAutomationVerdict verdict,
			double confidence, String reason, String model, Instant checkedAt, ReviewStatus status) {
		return new ContentAutomationCheck(postId, authorId, verdict, confidence, truncate(reason, 280), model,
			checkedAt, status);
	}

	public ContentAutomationCheck withStatus(ReviewStatus newStatus) {
		return new ContentAutomationCheck(postId, authorId, verdict, confidence, reason, model, checkedAt, newStatus);
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max);
	}
}
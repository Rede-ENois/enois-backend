package com.socialcoisa.rede.moderation.domain;

import java.time.Instant;

/**
 * Resultado da checagem de uma mídia (uma linha por {@code media_id}).
 *
 * <p>O registro existe por dois motivos: impede baixar e reenviar a mesma imagem ao modelo a cada
 * varredura, e deixa a decisão auditável — com que modelo, quando e com que confiança a conta foi
 * derrubada. Sem isso, um banimento automático seria uma caixa-preta.</p>
 */
public record MediaSafetyCheck(String mediaId, String ownerId, MediaSafetyVerdict verdict, String severity,
		double confidence, String reason, String model, Instant checkedAt) {

	/** Teto do motivo gravado: é uma frase do modelo, não um relatório. */
	private static final int MAX_REASON = 280;

	public static MediaSafetyCheck of(String mediaId, String ownerId, MediaSafetyVerdict verdict, String severity,
			double confidence, String reason, String model, Instant checkedAt) {
		String cleanReason = reason == null || reason.isBlank() ? verdict.name() : reason.trim();
		if (cleanReason.length() > MAX_REASON) {
			cleanReason = cleanReason.substring(0, MAX_REASON);
		}
		return new MediaSafetyCheck(mediaId, ownerId, verdict, severity == null ? "LOW" : severity,
			Math.max(0, Math.min(1, confidence)), cleanReason, model, checkedAt);
	}
}

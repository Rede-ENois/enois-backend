package com.socialcoisa.rede.moderation.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Resultado da checagem de um destino publicado (uma linha por URL normalizada).
 *
 * <p>A chave é o hash da URL: o endereço pode ter 900 caracteres e não serve como chave de índice.
 * O texto da URL fica gravado ao lado para a auditoria e para o recurso do autor.</p>
 */
public record LinkSafetyCheck(String urlHash, String url, String ownerId, LinkSafetyVerdict verdict,
		String severity, double confidence, String reason, String model, Instant checkedAt) {

	private static final int MAX_REASON = 280;

	private static final int MAX_URL = 1000;

	public static String hashOf(String url) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		}
		catch (Exception impossible) {
			throw new IllegalStateException("SHA-256 indisponivel", impossible);
		}
	}

	public static LinkSafetyCheck of(String url, String ownerId, LinkSafetyVerdict verdict, String severity,
			double confidence, String reason, String model, Instant checkedAt) {
		String cleanReason = reason == null || reason.isBlank() ? verdict.name() : reason.trim();
		if (cleanReason.length() > MAX_REASON) {
			cleanReason = cleanReason.substring(0, MAX_REASON);
		}
		String cleanUrl = url == null ? "" : url;
		if (cleanUrl.length() > MAX_URL) {
			cleanUrl = cleanUrl.substring(0, MAX_URL);
		}
		return new LinkSafetyCheck(hashOf(cleanUrl), cleanUrl, ownerId, verdict,
			severity == null ? "LOW" : severity, Math.max(0, Math.min(1, confidence)), cleanReason, model,
			checkedAt);
	}
}

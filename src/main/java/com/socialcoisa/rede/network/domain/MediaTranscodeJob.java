package com.socialcoisa.rede.network.domain;

import java.time.Instant;

/**
 * Um transcode de mídia delegado a um nó {@code NODE_MEDIA} (fase 3).
 *
 * <p>A linha é a autoridade sobre a verificação: o nó declara o que produziu, mas o que fica gravado
 * em {@code resultBytes}/{@code resultSha256} é o que o Control Plane <b>mediu</b> no objeto. Um nó
 * que declara um hash que não corresponde ao arquivo não consegue transformar isso em sucesso — a
 * comparação é feita contra o arquivo, não contra a declaração.</p>
 *
 * <p>O estado é monotônico: {@code PENDING} → {@code VERIFIED} ou {@code REJECTED}. Rejeitado volta
 * para a fila como nova tentativa da mesma tarefa; a linha continua {@code PENDING} até uma
 * tentativa conferir, e só então vira {@code VERIFIED}.</p>
 */
public record MediaTranscodeJob(String mediaId, String sourceKey, String mimeType, long sourceBytes,
		String sourceSha256, String resultKey, Long resultBytes, String resultSha256, MediaJobStatus status,
		String taskId, String lastError, Instant createdAt, Instant verifiedAt) {

	public MediaTranscodeJob {
		if (mediaId == null || mediaId.isBlank()) {
			throw new IllegalArgumentException("Job de midia sem mediaId.");
		}
		if (status == null) {
			throw new IllegalArgumentException("Job de midia sem status.");
		}
	}

	/** A origem que o Control Plane fixou no pedido. */
	public MediaTranscodeSpec spec(long resultBudgetBytes) {
		return new MediaTranscodeSpec(mediaId, sourceKey, mimeType, sourceBytes, sourceSha256, resultKey,
			resultBudgetBytes);
	}

	/** O resultado conferido: só existe depois de {@link #verified}. */
	public boolean verified() {
		return status == MediaJobStatus.VERIFIED;
	}

	/** O nó entregou um resultado que não confere com o que ele mesmo gravou. */
	public MediaTranscodeJob rejected(String reason, Instant now) {
		return new MediaTranscodeJob(mediaId, sourceKey, mimeType, sourceBytes, sourceSha256, resultKey, resultBytes,
			resultSha256, MediaJobStatus.REJECTED, taskId, truncate(reason), createdAt, verifiedAt);
	}

	/** O resultado conferiu: hash e tamanho medidos batem com o que o nó declarou. */
	public MediaTranscodeJob verified(long bytes, String sha256, Instant now) {
		return new MediaTranscodeJob(mediaId, sourceKey, mimeType, sourceBytes, sourceSha256, resultKey, bytes, sha256,
			MediaJobStatus.VERIFIED, taskId, null, createdAt, now);
	}

	/** O nó falhou de forma retentável: a linha continua PENDING e guarda o motivo para o painel. */
	public MediaTranscodeJob failed(String reason) {
		return new MediaTranscodeJob(mediaId, sourceKey, mimeType, sourceBytes, sourceSha256, resultKey, resultBytes,
			resultSha256, MediaJobStatus.PENDING, taskId, truncate(reason), createdAt, verifiedAt);
	}

	private static String truncate(String text) {
		if (text == null) {
			return null;
		}
		return text.length() > 500 ? text.substring(0, 500) : text;
	}
}

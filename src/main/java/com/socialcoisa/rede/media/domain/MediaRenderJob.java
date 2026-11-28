package com.socialcoisa.rede.media.domain;

import java.time.Instant;

/**
 * Pedido de render de páginas de um PDF (uma linha por documento).
 *
 * <p>Serve a duas coisas: não render o mesmo documento duas vezes, e separar "tentou e não
 * conseguiu" de "ainda não tentou" — o worker só recolhe o que está {@link MediaRenderJobStatus#PENDING},
 * e o documento já falho não é reprocessado a cada rodada.</p>
 */
public record MediaRenderJob(String mediaId, String ownerId, MediaRenderJobStatus status, int attempt, int pages,
		String pageKeys, String lastError, Instant createdAt, Instant updatedAt) {

	public static MediaRenderJob pending(String mediaId, String ownerId, Instant now) {
		return new MediaRenderJob(mediaId, ownerId, MediaRenderJobStatus.PENDING, 0, 0, null, null, now, now);
	}

	/**
	 * Página gravada, num render em curso: o pedido passa a RENDERED na hora. As folhas existem, e é
	 * isso que decide se a mídia pode ir ao ar — um pedido "em andamento" com página gravada é lido
	 * como documento sem página, e documento sem página fica fechado. A contagem e o registro final
	 * vêm com {@link #rendered}.
	 */
	public MediaRenderJob withPages(java.util.List<String> keys, Instant now) {
		return new MediaRenderJob(mediaId, ownerId, MediaRenderJobStatus.RENDERED, attempt + 1, keys.size(),
			String.join(",", keys), lastError, createdAt, now);
	}

	public MediaRenderJob rendered(java.util.List<String> keys, Instant now) {
		return new MediaRenderJob(mediaId, ownerId, MediaRenderJobStatus.RENDERED, attempt + 1, keys.size(),
			String.join(",", keys), null, createdAt, now);
	}

	/**
	 * Marca a falha. Páginas já gravadas mantêm o estado RENDERED e as chaves, mesmo quando o desenho
	 * parou no meio: elas existem, e é das chaves que a varredura tira o que falta olhar. FAILED é
	 * documento sem página.
	 */
	public MediaRenderJob failed(String error, Instant now) {
		String clipped = error == null ? null : error.length() > 300 ? error.substring(0, 300) : error;
		if (status == MediaRenderJobStatus.RENDERED) {
			return new MediaRenderJob(mediaId, ownerId, status, attempt, pages, pageKeys, clipped, createdAt, now);
		}
		return new MediaRenderJob(mediaId, ownerId, MediaRenderJobStatus.FAILED, attempt + 1, 0, null, clipped,
			createdAt, now);
	}

	public java.util.List<String> pageKeyList() {
		return pageKeys == null || pageKeys.isBlank() ? java.util.List.of() : java.util.List.of(pageKeys.split(","));
	}
}

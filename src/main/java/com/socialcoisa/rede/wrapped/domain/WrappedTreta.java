package com.socialcoisa.rede.wrapped.domain;

import java.time.Instant;

/**
 * Uma discussão em que a conta participou com peso: a publicação onde os comentários dela se
 * concentraram, quantos foram, em quantos dias distintos e por quanto tempo.
 *
 * <p>O nome é leve de propósito — é "treta" no sentido de papo quente, nunca rótulo moral. Não há
 * contagem de quem "está certo": só participação. O texto de terceiros não atravessa; o container
 * monta o link a partir do {@code publicacaoId}.</p>
 */
public record WrappedTreta(
		String publicacaoId,
		int comentarios,
		int diasDistintos,
		long duracaoDias,
		Instant primeira,
		Instant ultima,
		String trecho) {
}

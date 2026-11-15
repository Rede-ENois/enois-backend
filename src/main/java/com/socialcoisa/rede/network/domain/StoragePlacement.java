package com.socialcoisa.rede.network.domain;

import java.time.Instant;

/**
 * Uma cópia de um objeto num nó do Storage (V320).
 *
 * <p>{@code replica} é o número da cópia, não uma contagem: a fase 1 grava sempre 0 (uma cópia) e a
 * fase 3 acrescenta 1..N-1 sem alterar a tabela nem as linhas existentes. A chave primária é
 * {@code (objectId, replica)} justamente por isso.</p>
 *
 * <p>{@code bytes} e {@code sha256} são do que o Control Plane <b>mediu</b> ao receber o corpo — não
 * do que o nó declarou. É o mesmo critério do transcode de mídia ({@code MediaResultVerifier}): o
 * que fica guardado é o medido, porque "o nó disse que gravou" não é evidência de durabilidade.</p>
 */
public record StoragePlacement(String objectId, int replica, String nodeId, long bytes, String sha256,
		State state, Instant verifiedAt) {

	/** Estado de uma cópia. O objeto pode estar STORED com uma cópia STALE ao lado. */
	public enum State {
		/** Cópia inteira e conferida na gravação. */
		OK,
		/** O nó foi suspenso/revogado ou não respondeu: a cópia não é servível, mas o disco dele ainda tem os bytes. */
		STALE,
		/** A coleta já removeu a cópia do nó (ou confirmou que não havia mais nada lá). */
		GONE
	}
}
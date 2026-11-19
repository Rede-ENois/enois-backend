package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * O que os nós de reserva guardaram do banco do Control Plane (V360, brief cooperativo §11/§12).
 *
 * <p>A tabela diz o que o nó <b>afirma</b> ter. A verdade transcrita (linha por linha, com digest e
 * tudo) é conferida pelo nó contra o que ele gravou; o Control Plane só registra a cópia inteira
 * concluída. Cópia parcial nunca vira {@code READY}: assumir com metade do banco é pior do que
 * assumir sem banco, porque parece que funcionou.</p>
 */
public interface StandbySnapshotRepository {

	/**
	 * Estado de uma cópia em andamento.
	 *
	 * @param state {@code SYNCING}
	 * @param byteStart primeiro byte enviado (0 = desde o começo; &gt; 0 = retomada)
	 */
	record Sync(int term, long byteStart, Instant startedAt) {
	}

	/**
	 * A última cópia completa de um nó.
	 *
	 * @param stale {@code true} quando a cópia está velha (passou de um dia sem cópia boa)
	 */
	record Ready(long bytes, String sha256, int term, Instant completedAt, boolean stale) {

		String state() {
			return stale ? "STALE" : "READY";
		}
	}

	/** Uma linha do painel: nó de reserva, autoridade de origem e o que a cópia prova. */
	record Snapshot(String nodeId, String authorityId, String state, int term, Instant startedAt,
			Instant completedAt, Long bytes, long byteStart, String sha256, String failureReason,
			String requestedBy) {
	}

	/** O par é único: lê a linha do nó de reserva para a autoridade; vazio se nunca houve cópia. */
	Optional<Snapshot> find(String nodeId, String authorityId);

	/**
	 * Marca o início de uma tentativa, preservando a cópia anterior (ela continua sendo a última
	 * completa até a nova terminar). {@code byteStart > 0} é retomada.
	 */
	void startSync(String nodeId, String authorityId, int term, long byteStart, String requestedBy, Instant now);

	/**
	 * Registra a cópia inteira concluída e conferida pelo nó. Só acontece com o fim do curso: quem
	 * falha no meio nunca grava aqui.
	 */
	void markReady(String nodeId, String authorityId, long bytes, String sha256, int term, Instant now);

	/** A tentativa terminou sem cópia. A cópia anterior, se houver, continua registrada como estava. */
	void markFailed(String nodeId, String authorityId, String reason, Instant now);

	/**
	 * Quantas cópias boas um nó tem (quantas autoridades ele poderia assumir). É a pergunta do
	 * failover: zero significa que este nó só pode assumir depois de uma sincronização completa.
	 */
	int readyCount(String nodeId);

	/**
	 * Cópias completas mais novas que o instante, de qualquer nó. É o critério do refresh
	 * automático (pelo menos uma cópia do dia, antes da sincronização completa).
	 */
	boolean anyReadySince(Instant since);

	/** Todas as cópias de todos os nós, da mais recente para a mais antiga (painel de admin). */
	List<Snapshot> all();
}
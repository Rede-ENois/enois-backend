package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.ContentChange;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;

/** Versão do conteúdo público (V279), o feed de mudanças (V283) e o que cada nó já sincronizou. */
public interface ContentVersionRepository {

	Optional<ContentVersion> find(ContentKind kind, String contentId);

	/**
	 * Grava a versão nova. A condição é a versão esperada: duas instâncias que calcularam o mesmo
	 * corpo ao mesmo tempo não cunham duas versões — a segunda não encontra a linha na versão
	 * esperada e não escreve.
	 *
	 * @return true se gravou
	 */
	boolean save(ContentVersion version, long expectedVersion);

	/** O que o nó já tem deste agregado; vazio = nunca sincronizou. */
	Optional<NodeSnapshot> snapshot(String nodeId, ContentKind kind, String contentId);

	/** Registra o que o nó baixou. Reenvio do mesmo conteúdo atualiza a hora, não a versão. */
	void recordSnapshot(String nodeId, ContentKind kind, String contentId, long sourceVersion, String contentHash,
			Instant now);

	/** Quantos agregados deste tipo o nó tem em atraso (versão do nó menor que a da origem). */
	long countBehind(String nodeId, ContentKind kind);

	/**
	 * Os snapshots que a varredura deve conferir agora, de todos os nós: os nunca conferidos
	 * primeiro, depois os conferidos há mais tempo. É o que faz a cobertura ser justa — um nó com
	 * milhares de agregados é coberto em rodadas, e não sempre os mesmos primeiros.
	 */
	List<NodeSnapshot> dueForCheck(int limit);

	/**
	 * Grava o resultado da conferência, condicionado ao motivo anterior.
	 *
	 * <p>A condição é o que evita auditoria duplicada: duas instâncias varrendo ao mesmo tempo leem
	 * o mesmo snapshot, mas só a que encontrar a linha no motivo esperado escreve e audita. A outra
	 * atualiza zero linhas e não repete o alerta.</p>
	 *
	 * @param previousReason motivo vigente lido antes da conferência; nulo = estava em dia
	 * @param newReason motivo encontrado agora; nulo = está em dia
	 * @param divergenceAt quando a divergência vigente começou (preservado quando o motivo não muda)
	 * @return 1 se esta chamada foi a que gravou
	 */
	int markChecked(String nodeId, ContentKind kind, String contentId, String previousReason, String newReason,
			Instant divergenceAt, Instant now);

	/** Snapshots com divergência vigente, do mais recente para o mais antigo. É a lista do painel. */
	List<NodeSnapshot> divergent(int limit);

	/** Quantos snapshots estão divergentes agora. */
	long countDivergent();

	/** Quantos snapshots existem (o denominador do painel). */
	long countAll();

	/**
	 * Registra no feed que esta versão foi cunhada (V283).
	 *
	 * <p>Chamado na MESMA transação da cunhagem: versão nova sem linha no feed seria uma mudança que
	 * nenhum nó descobre, e o cache de borda ficaria velho até o TTL — no pior caso, para sempre, se
	 * o agregado nunca mais for editado.</p>
	 */
	void recordChange(ContentVersion version);

	/** O feed depois de {@code afterSeq}, em ordem de {@code seq}. Vazio = o nó está em dia. */
	List<ContentChange> changesAfter(long afterSeq, int limit);

	/** A posição mais alta já gravada no feed; 0 quando o feed está vazio. */
	long maxSeq();

	/**
	 * A menor posição ainda retida no feed; vazio = feed vazio.
	 *
	 * <p>É a fronteira da poda. Um nó que parou num {@code seq} menor do que este perdeu mudanças que
	 * já não existem para reler, e responder "nada mudou" o deixaria servindo conteúdo velho para
	 * sempre. Quem chama precisa poder pedir recarga completa nesse caso.</p>
	 */
	Optional<Long> oldestRetainedSeq();

	/** Poda o feed: as posições mais antigas que {@code before}, no máximo {@code limit}. */
	List<Long> expiredChangeSeqs(Instant before, int limit);

	/** Remove as posições informadas. Devolve quantas saíram. */
	int deleteChanges(List<Long> seqs);

	/**
	 * @param contentHash sha-256 hex do corpo servido, para a reconciliação comparar byte a byte
	 * @param checkedAt quando a varredura conferiu esta linha; nulo = nunca conferida
	 * @param divergenceReason motivo da divergência vigente; nulo = em dia
	 * @param divergenceAt quando a divergência vigente foi detectada; nulo = em dia
	 */
	record NodeSnapshot(String nodeId, ContentKind kind, String contentId, long sourceVersion, String contentHash,
			Instant syncedAt, Instant checkedAt, String divergenceReason, Instant divergenceAt) {
	}
}

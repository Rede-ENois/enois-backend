package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.ContentChange;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.ContentVersion;

/** Versão do conteúdo público (V294), o feed de mudanças (V298) e o que cada nó já sincronizou. */
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
	 *
	 * @param evacuatedRegions regiões declaradas fora de operação; o snapshot de nó delas fica fora
	 * da fila, porque conferir um nó que a própria rede tirou de operação acusaria o nó por uma
	 * decisão do Control Plane (a região saiu, o nó não divergiu)
	 */
	List<NodeSnapshot> dueForCheck(Collection<String> evacuatedRegions, int limit);

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
	 * Registra no feed que esta versão foi cunhada (V298).
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
	 * Quantas regiões distintas têm, agora, uma cópia utilizável de cada tipo de conteúdo.
	 *
	 * <p>É a medida que separa "replicado" de "replicado <b>geograficamente</b>": uma dúzia de nós de
	 * borda no mesmo rack tem doze cópias e uma região só. A conta olha apenas nó operável — ACTIVE,
	 * com heartbeat recente e <b>não</b> de região fora de operação —, porque a cópia de um nó que a
	 * rede tirou de operação não cobre nada.</p>
	 *
	 * <p>Nó sem região declarada conta como uma região própria identificada por {@code ""}: ele tem
	 * cópia, e ignorá-lo faria o painel subnotificar cobertura em rede que não usa o campo. É leitura
	 * de painel, não decisão de despacho.</p>
	 *
	 * @param evacuatedRegions regiões declaradas fora de operação; o nó delas não conta
	 * @param aliveSince corte de heartbeat: nó que parou de bater não cobre região nenhuma
	 */
	List<RegionCoverage> coverage(Collection<String> evacuatedRegions, Instant aliveSince);

	/**
	 * Cobertura de um tipo de conteúdo: em quantas regiões ele tem cópia, e quantas regiões a rede
	 * tem ao todo para comparar.
	 *
	 * @param kind tipo do conteúdo (a allowlist)
	 * @param regionsWithCopy regiões com pelo menos um nó operável que confirmou ter este tipo
	 * @param totalRegions regiões com pelo menos um nó operável, do mesmo universo usado acima
	 * @param copies quantos nós confirmaram ter este tipo (a redundância dentro da cobertura)
	 */
	record RegionCoverage(ContentKind kind, long regionsWithCopy, long totalRegions, long copies) {
	}

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

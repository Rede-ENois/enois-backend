package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * O catálogo do Énóis Storage (V319/V320): o objeto lógico e a colocação das cópias nos nós.
 *
 * <p>Esta porta é a fronteira que o brief exige: o resto do backend pergunta ao catálogo "onde
 * está este objeto?" e nunca fala com um storage node. A localização física muda (replicação,
 * healing, nó que sai) sem que nada acima saiba.</p>
 */
public interface StorageCatalogRepository {

	/** O objeto por bucket e chave, inclusive lapidado — a lápide também precisa responder "já existiu". */
	Optional<StorageObject> find(String bucket, String key);

	/** O objeto por id, para as operações que já o resolveram (upload, download, remoção). */
	Optional<StorageObject> findById(String id);

	/**
	 * O objeto pelo sha-256 do conteúdo (fase 3), para a réplica que conhece a cópia pelo digest e
	 * não pelo id lógico. Só objetos com conteúdo medido têm digest único: dois {@code PENDING} não
	 * têm {@code sha256}, e dois objetos distintos com o mesmo conteúdo compartilham o digest — o
	 * que é próprio do identificador de conteúdo.
	 */
	Optional<StorageObject> findByDigest(String sha256);

	/**
	 * Cria o objeto em {@code PENDING}, ou devolve o existente. A segunda chamada do mesmo cliente
	 * não duplica: {@code bucket + key} é única no banco e a consulta é o que decide.
	 */
	StorageObject createPending(String id, String bucket, String key, String contentType, String createdBy,
			Instant now);

	/** O objeto ganhou cópia inteira: bytes e hash medidos, estado {@code STORED}. */
	boolean markStored(String id, long bytes, String sha256, Instant now);

	/** Lápide. Devolve {@code false} se já estava removido — repetir a remoção não é erro. */
	boolean markDeleted(String id, Instant now);

	/**
	 * Listagem por prefixo de chave, ordenada por chave. {@code limit + 1} linhas é o que o chamador
	 * pede para saber se há próxima página sem uma segunda consulta de contagem.
	 */
	List<StorageObject> list(String bucket, String prefix, String afterKey, int limit);

	/**
	 * Todos os objetos servíveis, ordenados por {@code bucket, object_key} — a paginação da varredura
	 * percorre o catálogo inteiro, não um bucket. O cursor é a chave: dois buckets podem ter a mesma
	 * chave, e o cursor por chave visita as duas na mesma página — o que não perde nem duplica
	 * objeto, porque a ordenação é total por {@code bucket, object_key}.
	 */
	List<StorageObject> listAll(String afterKey, int limit);

	/** Grava (ou substitui) a cópia {@code replica} deste objeto neste nó. */
	void savePlacement(StoragePlacement placement);

	/**
	 * Todas as cópias deste objeto em <b>qualquer</b> estado, em ordem de réplica.
	 *
	 * <p>É a leitura que a durabilidade usa, e o nome diz isso: uma cópia {@code GONE} ainda conta
	 * como réplica existente. Quem pergunta "quantas cópias o objeto tem" precisa deste número —
	 * {@code placements} descreve o que é <i>servível</i>, que é outra pergunta.</p>
	 */
	List<StoragePlacement> copies(String objectId);

	/**
	 * As cópias deste objeto ainda servíveis (estado {@code OK}), em ordem de réplica.
	 *
	 * <p>Uso de leitura/serving: objeto que voltou a ter cópia confirmada. Para durabilidade use
	 * {@link #copies(String)}.</p>
	 */
	List<StoragePlacement> placements(String objectId);

	/**
	 * Lápides vencidas, em ordem total {@code (bucket, object_key)}, estritamente <b>depois</b> do
	 * cursor. O cursor é o par {@code (bucket, chave)} e não só a chave: a mesma chave em dois
	 * buckets é legítima, e um cursor só pela chave oscilaria entre as duas linhas para sempre.
	 *
	 * <p>Cada linha devolvida está além do cursor, então uma página que não rendeu (todos os objetos
	 * pulados) ainda avança — nenhuma chamada repete o que já foi decidido.</p>
	 */
	List<StorageObject> listDeletedBefore(Instant before, String afterBucket, String afterKey, int limit);

	/** A cópia servível deste objeto num nó específico, se existir. */
	/**
	 * A cópia deste objeto neste nó em qualquer estado vivo ({@code OK} ou {@code PENDING_REMOTE}).
	 * A fase 3 usa esta leitura para a própria designação se confirmar; filtrar por {@code OK} aqui
	 * esconderia a linha que a designação acabou de criar.
	 */
	Optional<StoragePlacement> placementOn(String objectId, String nodeId);

	/**
	 * Objetos do catálogo inteiro por estado: {@code {quantidade, bytes}} cada. Uma consulta só, para
	 * o painel — varrer o catálogo para somar seria contar linha a linha o que o banco já sabe.
	 *
	 * <p>Estado ausente no mapa é "nenhum objeto naquele estado", que é diferente de zero objetos
	 * encontrados. Quem consome deve tratar a ausência como zero explícito na soma, nunca como
	 * "não medido".</p>
	 */
	java.util.Map<String, long[]> summarizeObjects();

	/** Cópias por estado: {@code {quantidade, bytes}} cada. Mesma regra de ausência acima. */
	java.util.Map<String, long[]> summarizePlacements();

	/** O que este nó hospeda, para o painel e para o nó saber o que tem. */
	List<StoragePlacement> placementsOnNode(String nodeId);
}
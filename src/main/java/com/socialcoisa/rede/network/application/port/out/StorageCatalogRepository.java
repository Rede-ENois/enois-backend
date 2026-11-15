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

	/** Grava (ou substitui) a cópia {@code replica} deste objeto neste nó. */
	void savePlacement(StoragePlacement placement);

	/** As cópias deste objeto ainda servíveis (estado {@code OK}), em ordem de réplica. */
	List<StoragePlacement> placements(String objectId);

	/** A cópia servível deste objeto num nó específico, se existir. */
	Optional<StoragePlacement> placementOn(String objectId, String nodeId);

	/** O que este nó hospeda, para o painel e para o nó saber o que tem. */
	List<StoragePlacement> placementsOnNode(String nodeId);
}
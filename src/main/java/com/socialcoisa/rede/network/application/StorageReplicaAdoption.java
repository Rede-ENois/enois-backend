package com.socialcoisa.rede.network.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;

/**
 * A cópia que só existe na réplica 0 ganha uma cópia de nó (healing de "réplica única").
 *
 * <p><b>O buraco que isto fecha.</b> A réplica 0 mora no disco do Control Plane. A fase 3 conta
 * durabilidade por <b>cópia de nó</b>: um objeto com a réplica 0 e nenhuma cópia {@code OK} está a
 * uma perda de disco de sumir, e — desde o §5.9.2 do doc 13 — quando o Control Plane que atende não é
 * o dono da réplica 0 o próprio download responde 409. A designação de réplica existe, o
 * {@code OBJECT_PUT} existe, a confirmação existe; faltava quem <b>pedisse</b> a cópia de um objeto
 * que nunca teve nenhuma (upload com o nó sem folga, nenhum nó elegível naquele instante, ou objeto
 * anterior à colocação).</p>
 *
 * <p><b>Por que não é automático na criação.</b> Pedir réplica é transferência pela rede de quem
 * participa; o módulo trata movimentação como ato de operador (doc 17 §2). Esta classe é a mão do
 * operador e é <b>idempotente</b>: a única saída garantida é o objeto passar a ter uma designação —
 * e, na segunda chamada, já não é candidato porque tem cópia de nó.</p>
 *
 * <p><b>O que ela não faz:</b> não substitui cópia {@code STALE}/{@code GONE} (healing de cópia ruim
 * tem caminho próprio, doc 13 §5.1.1), não move nada, e não toca em objeto que já tem qualquer
 * colocação de nó.</p>
 */
@Service
public class StorageReplicaAdoption {

	/** Teto por chamada: o relatório é para ser lido, não é varredura. */
	static final int MAX_OBJECTS = 200;

	public record Adopted(String objectId, String bucket, String key, String nodeId) {
	}

	/** O que ficou sem designação, e por quê — nenhum "não sei" escondido. */
	public record Skipped(String objectId, String bucket, String key, String reason) {
	}

	public record Report(int considered, List<Adopted> adopted, List<Skipped> skipped) {
	}

	private final StorageCatalogRepository catalog;
	private final StoragePlacementService placement;
	private final Clock clock;

	StorageReplicaAdoption(StorageCatalogRepository catalog, StoragePlacementService placement, Clock clock) {
		this.catalog = catalog;
		this.placement = placement;
		this.clock = clock;
	}

	/** O que hoje está só na réplica 0, sem gravar nada. */
	public Report preview() {
		return run(true);
	}

	/**
	 * Cria a designação de uma cópia para cada objeto que só tem a réplica 0. {@code dryRun} mostra o
	 * que aconteceria; a rota usa o padrão do módulo (seco primeiro).
	 *
	 * <p>A escolha do nó reusa {@link StoragePlacementService#chooseReplica}: folga <b>cedida</b>
	 * medida, domínio de falha e região são as mesmas regras da colocação do upload — uma segunda
	 * régua aqui seria uma segunda resposta para "onde cabe".</p>
	 */
	@Transactional
	public Report run(boolean dryRun) {
		// O repositório devolve os que têm SÓ a réplica 0; a checagem de `copies` dentro do laço é a
		// trava contra corrida e contra quem já pediu a transferência nesta mesma chamada.
		List<StorageObject> candidatos = catalog.withOnlyReplicaZero(MAX_OBJECTS);
		Instant now = clock.instant();
		List<Adopted> adotados = new ArrayList<>();
		List<Skipped> pulados = new ArrayList<>();
		for (StorageObject object : candidatos) {
			// Sem corpo medido não há o que replicar: o `OBJECT_PUT` manda sha-256 e bytes.
			if (!object.servable() || object.sha256() == null || object.bytes() == null) {
				pulados.add(new Skipped(object.id(), object.bucket(), object.key(), "OBJECT_NOT_SERVABLE"));
				continue;
			}
			// `chooseReplica` só enxerga a colocação OK (é o que decide durabilidade); aqui as
			// PENDING_REMOTE também importam — são pedidos de transferência que já saíram e a segunda
			// chamada não pode pedir outra cópia para o mesmo objeto. É por isso que a idempotência
			// não depende do nó ter confirmado.
			if (!catalog.copies(object.id()).isEmpty()) {
				pulados.add(new Skipped(object.id(), object.bucket(), object.key(), "REPLICA_ALREADY_ASSIGNED"));
				continue;
			}
			Optional<NetworkNode> destino = placement.chooseReplica(object.id(), object.bytes(), now);
			if (destino.isEmpty()) {
				pulados.add(new Skipped(object.id(), object.bucket(), object.key(), "NO_NODE_ELIGIBLE"));
				continue;
			}
			if (!dryRun) {
				catalog.savePlacement(new StoragePlacement(object.id(), 1, destino.get().id(), object.bytes(),
					object.sha256(), StoragePlacement.State.PENDING_REMOTE, now));
			}
			adotados.add(new Adopted(object.id(), object.bucket(), object.key(), destino.get().id()));
		}
		return new Report(candidatos.size(), List.copyOf(adotados), List.copyOf(pulados));
	}
}
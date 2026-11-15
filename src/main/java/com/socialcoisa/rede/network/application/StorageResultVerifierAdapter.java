package com.socialcoisa.rede.network.application;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.application.port.out.StorageResultVerifier;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Confere o {@code OBJECT_PUT} que o nó declarou concluído <b>contra o que o Control Plane já mediu</b>.
 *
 * <h2>A regra</h2>
 * <p>O nó pode declarar sucesso; quem decide se é sucesso é o catálogo. Um {@code OBJECT_PUT}
 * ACEITO sem cópia confirmada daquele nó é a pior classe de defeito do módulo: a colocação diz
 * "existe cópia", o {@code /storage/scrub} concorda, o healing deixa de procurar — e o objeto está
 * em um lugar só. É durabilidade inventada, e o operador só descobre quando precisa dela.</p>
 *
 * <p>Três condições, todas necessárias:</p>
 * <ol>
 * <li>existe colocação <b>{@code OK}</b> deste objeto <b>neste</b> nó — a confirmação é o que o nó
 * enviou ao {@code POST .../replica}, e ela já confrontou bytes e sha-256 com o medido;</li>
 * <li>os {@code bytes} declarados no {@code output}, se vierem, são os medidos pelo catálogo;</li>
 * <li>o {@code sha256} declarado, se vier, é o medido pelo catálogo.</li>
 * </ol>
 *
 * <h2>Por que "se vierem" e não exigir</h2>
 * <p>Exigir campos no {@code output} seria criar um segundo contrato de confirmação ao lado do
 * {@code POST .../replica}, que já é o contrato — e um runtime antigo passaria a ser recusado por
 * formato, não por falta de cópia. O que se recusa é a <b>divergência</b>: número declarado que o
 * catálogo contradiz é afirmação falsa, e aceitá-la ensinaria o nó a mentir.</p>
 *
 * <p>Achado no caminho: o {@code *) } do {@code node-runtime.sh} responde {@code DONE} com um eco do
 * {@code idempotencyKey} para qualquer tipo sem executor — inclusive {@code OBJECT_PUT}. Com este
 * conferente, esse eco passa a ser recusado <b>pela ausência de cópia</b>, que é o que de fato
 * aconteceu.</p>
 */
@Component
class StorageResultVerifierAdapter implements StorageResultVerifier {

	/** Mesmo critério do healing: lacuna que já tem designação é retentável, conteúdo errado não. */
	private static final boolean RETRY_WHEN_MISSING = true;

	private final StorageCatalogRepository catalog;
	private final ObjectMapper objectMapper;

	StorageResultVerifierAdapter(StorageCatalogRepository catalog, ObjectMapper objectMapper) {
		this.catalog = catalog;
		this.objectMapper = objectMapper;
	}

	@Override
	public Verification verify(NetworkTask task, String output) {
		String objectId = objectIdOf(task);
		if (objectId == null) {
			// Sem objeto no payload não há o que conferir, e aceitar seria aceitar nada. Recusar sem
			// retry: a tarefa é malformada, tentar de novo daria o mesmo payload.
			return Verification.refused("STORAGE_TASK_PAYLOAD_INVALID",
				"A tarefa OBJECT_PUT nao diz qual objeto replicar.", false);
		}
		StorageObject object = catalog.findById(objectId).orElse(null);
		if (object == null) {
			return Verification.refused("STORAGE_OBJECT_UNKNOWN", "O objeto nao existe no catalogo.", false);
		}
		List<StoragePlacement> copias = catalog.placements(objectId);
		Optional<StoragePlacement> confirmada = copias.stream()
			.filter(p -> task.assignedNodeId() != null && task.assignedNodeId().equals(p.nodeId()))
			.filter(p -> p.state() == StoragePlacement.State.OK)
			.findFirst();
		if (confirmada.isEmpty()) {
			// A causa mais provável de retentativa em Storage: o nó ainda ia confirmar, ou o disco
			// abaixo do piso impediu o aceite. A tarefa volta e o healing não perde o rastro.
			return Verification.refused("STORAGE_REPLICA_NOT_CONFIRMED",
				"O no declarou a replica sem copia confirmada no catalogo.", RETRY_WHEN_MISSING);
		}
		var declarado = parse(output);
		JsonNode bytes = declarado == null ? null : declarado.get("bytes");
		if (bytes != null && !bytes.isNull()) {
			Long medido = object.bytes();
			if (medido == null || bytes.asLong(-1) != medido) {
				return Verification.refused("STORAGE_REPLICA_MEASURE_MISMATCH",
					"Bytes declarados nao conferem com os medidos pelo Control Plane.", false);
			}
		}
		JsonNode sha = declarado == null ? null : declarado.get("sha256");
		if (sha != null && !sha.isNull() && (object.sha256() == null || !sha.asString().equals(object.sha256()))) {
			return Verification.refused("STORAGE_REPLICA_MEASURE_MISMATCH",
				"Sha-256 declarado nao confere com o medido pelo Control Plane.", false);
		}
		return Verification.accepted();
	}

	/** O {@code objectId} do payload — o mesmo formato de texto JSON que o despacho usa. */
	private String objectIdOf(NetworkTask task) {
		String payload = task.payload();
		if (payload == null || payload.isBlank()) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(payload);
			JsonNode id = root == null ? null : root.get("objectId");
			return id != null && id.isString() && !id.asString().isBlank() ? id.asString() : null;
		}
		catch (RuntimeException malformed) {
			return null;
		}
	}

	private JsonNode parse(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		try {
			return objectMapper.readTree(output);
		}
		catch (RuntimeException malformed) {
			// Output que não é JSON: não há declaração a contradizer. A cópia confirmada continua
			// sendo a prova; só a afirmação sobre ela é que não existe.
			return null;
		}
	}
}
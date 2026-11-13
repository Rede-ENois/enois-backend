package com.socialcoisa.rede.network.application;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.ContentVersionRepository;
import com.socialcoisa.rede.network.application.port.out.EdgeDeliverVerifier;
import com.socialcoisa.rede.network.domain.ContentKind;
import com.socialcoisa.rede.network.domain.NetworkTask;
import com.socialcoisa.rede.shared.audit.AuditTrail;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Confere o veredicto de entrega contra o que o próprio nó confirmou ter.
 *
 * <p>O nó atesta capacidade em dois passos que já existem no contrato: ele lê o conteúdo pelo
 * caminho autorizado da borda e <b>confirma</b> o que guardou em {@code POST
 * /network/content/{kind}/{id}/synced}, que grava {@code network_content_snapshots}. O veredicto
 * de entrega é então conferido contra o snapshot — versão e hash —, não contra a fala do nó.</p>
 *
 * <p>Os três jeitos de errar que isto fecha, e cada um tem status próprio para o painel:</p>
 *
 * <ul>
 * <li><b>Veredicto sem confirmação</b> ({@code EDGE_DELIVER_UNCONFIRMED}): o nó diz que entrega,
 * mas nunca confirmou à origem. É a declaração sem lastro — reenviar não adianta porque o passo
 * de confirmação não aconteceu.</li>
 * <li><b>Versão ou hash diferentes do confirmado</b> ({@code EDGE_DELIVER_MISMATCH}): o nó tem uma
 * entrada, mas o que ele diz ter não é o que ele gravou. Retentável: a próxima tentativa relê o
 * cache e reconfirma do zero.</li>
 * <li><b>Veredicto malformado</b> ({@code EDGE_DELIVER_INVALID}): sem {@code kind}, {@code
 * contentId}, {@code version} ou {@code deliverable}. Também retentável, porque o executor é do
 * nó e a recusa é do dado, não do pedido.</li>
 * </ul>
 *
 * <p>Veredicto negativo (<code>deliverable=false</code>) não chega aqui como sucesso: o executor
 * conclui a tarefa com o veredicto, e concluir com uma declaração negativa é <b>recusado</b> —
 * uma tarefa de entrega que termina "não entreguei" não é entrega concluída.</p>
 */
@Service
class EdgeDeliveryVerificationService implements EdgeDeliverVerifier {

	private final ContentVersionRepository versions;
	private final AuditTrail audit;
	private final ObjectMapper objectMapper;

	EdgeDeliveryVerificationService(ContentVersionRepository versions, AuditTrail audit, ObjectMapper objectMapper) {
		this.versions = versions;
		this.audit = audit;
		this.objectMapper = objectMapper;
	}

	@Override
	public Verification verify(NetworkTask task, String output) {
		Declared declared = parse(output);
		if (declared == null) {
			return Verification.refused("EDGE_DELIVER_INVALID", "Veredicto de entrega ausente ou malformado.", true);
		}
		if (!declared.deliverable()) {
			// Concluir como sucesso afirmando "não entreguei" seria contar entrega que não houve.
			audit.record(task.assignedNodeId(), "NETWORK_EDGE_DELIVER_REFUSED", task.id(),
				"{\"reason\":\"" + declared.reason() + "\"}");
			return Verification.refused("EDGE_DELIVER_NOT_DELIVERED",
				"O no declarou que nao consegue entregar o conteudo.", false);
		}
		Optional<ContentVersionRepository.NodeSnapshot> snapshot = versions.snapshot(task.assignedNodeId(),
			declared.kind(), declared.contentId());
		if (snapshot.isEmpty()) {
			return Verification.refused("EDGE_DELIVER_UNCONFIRMED",
				"O no nao confirmou ter guardado o conteudo.", false);
		}
		ContentVersionRepository.NodeSnapshot confirmed = snapshot.get();
		if (confirmed.sourceVersion() != declared.version()
				|| !declared.contentHash().equalsIgnoreCase(confirmed.contentHash())) {
			audit.record(task.assignedNodeId(), "NETWORK_EDGE_DELIVER_REJECTED", task.id(),
				"{\"declaredVersion\":" + declared.version() + ",\"confirmedVersion\":" + confirmed.sourceVersion()
					+ "}");
			return Verification.refused("EDGE_DELIVER_MISMATCH",
				"O veredicto nao corresponde ao conteudo confirmado.", true);
		}
		audit.record(task.assignedNodeId(), "NETWORK_EDGE_DELIVER_VERIFIED", task.id(),
			"{\"kind\":\"" + declared.kind() + "\",\"version\":" + declared.version() + "}");
		return Verification.accepted();
	}

	/**
	 * Lê o veredicto. Exige {@code deliverable} explícito: ausência de campo não é "sim" nem
	 * "não", e tratar ausência como sim seria aceitar qualquer corpo vazio como entrega.
	 */
	private Declared parse(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(output);
			if (root == null || !root.isObject()) {
				return null;
			}
			ContentKind kind = ContentKind.parse(textOf(root, "kind")).orElse(null);
			String contentId = textOf(root, "contentId");
			JsonNode deliverableNode = root.get("deliverable");
			if (kind == null || contentId == null || deliverableNode == null || !deliverableNode.isBoolean()) {
				return null;
			}
			boolean deliverable = deliverableNode.asBoolean();
			String reason = textOf(root, "reason");
			if (!deliverable) {
				return new Declared(kind, contentId, 0, "", false, reason == null ? "EDGE_NOT_DELIVERABLE" : reason);
			}
			JsonNode versionNode = root.get("version");
			String hash = textOf(root, "contentHash");
			if (versionNode == null || !versionNode.canConvertToLong() || versionNode.asLong() < 1 || hash == null) {
				return null;
			}
			// Versão sozinha não identifica o corpo: o contador sobe, mas o que prova que o nó guardou
			// ESTE corpo é o hash — o mesmo que ele confirmou em /synced.
			return new Declared(kind, contentId, versionNode.asLong(), hash, true, "");
		}
		catch (RuntimeException malformed) {
			return null;
		}
	}

	private static String textOf(JsonNode root, String field) {
		JsonNode value = root.get(field);
		return value == null || !value.isString() || value.asString().isBlank() ? null : value.asString();
	}

	private record Declared(ContentKind kind, String contentId, long version, String contentHash, boolean deliverable,
			String reason) {
	}
}
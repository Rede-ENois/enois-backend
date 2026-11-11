package com.socialcoisa.rede.network.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NodeKeyRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.security.Hashes;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * API key de um nó da rede (ADR-002): uma chave por nó, gerada por um administrador, exibida uma
 * única vez e persistida só como hash.
 *
 * <p>A chave em claro não é logada, auditada nem publicada em evento — o que fica registrado é o
 * prefixo ({@code enk_nd_ab12}), suficiente para reconhecer a chave numa varredura de segredo sem
 * revelar nada.</p>
 */
@Service
public class NodeKeyService {

	/** Prefixo reconhecível da chave de nó; nunca é segredo por si só. */
	static final String PREFIX = "enk_";
	/** 32 bytes de entropia: o mesmo piso que o resto do projeto usa para token de uso único. */
	private static final int KEY_BYTES = 32;
	/** Fatia do id que aparece no prefixo, só para diagnóstico humano. */
	private static final int NODE_ID_SLICE = 6;
	private static final int PREFIX_LENGTH = 16;

	private final SecureRandom random = new SecureRandom();

	private final NodeKeyRepository keys;
	private final NetworkNodeRepository nodes;
	private final AuditTrail audit;
	private final Clock clock;

	NodeKeyService(NodeKeyRepository keys, NetworkNodeRepository nodes, AuditTrail audit, Clock clock) {
		this.keys = keys;
		this.nodes = nodes;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Emite a chave do nó, revogando a anterior na mesma transação. A chave em claro só existe nesta
	 * resposta; a tela precisa avisar que ela não será mostrada de novo.
	 */
	@Transactional
	public IssuedKey issue(String nodeId, String actorId) {
		NetworkNode node = requireNode(nodeId);
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(409, "NODE_REVOKED", "No revogado nao recebe chave nova.");
		}
		keys.revokeActiveByNode(nodeId, actorId, clock.instant());
		String secret = randomSecret();
		String head = PREFIX + shortId(nodeId) + "_";
		String plain = head + secret;
		String keyId = NodeIds.next("nk");
		String prefix = truncate(head);
		keys.insert(keyId, nodeId, Hashes.sha256Hex(plain), prefix, actorId, clock.instant());
		audit.record(actorId, "NODE_KEY_ISSUED", nodeId, "{\"keyId\":\"" + keyId + "\",\"prefix\":\"" + prefix + "\"}");
		return new IssuedKey(keyId, plain, prefix);
	}

	/** Revoga uma chave específica do nó. Revogar chave inexistente ou já revogada é 404 confesso. */
	@Transactional
	public void revoke(String nodeId, String keyId, String actorId) {
		requireNode(nodeId);
		if (keys.revokeById(keyId, actorId, clock.instant()) == 0) {
			throw new ApiException(404, "NODE_KEY_NOT_FOUND", "Chave ativa nao encontrada.");
		}
		audit.record(actorId, "NODE_KEY_REVOKED", nodeId, "{\"keyId\":\"" + keyId + "\"}");
	}

	/** Revoga toda chave vigente do nó (usado quando o nó é revogado). Não falha sem chave ativa. */
	@Transactional
	public void revokeAllForNode(String nodeId, String actorId) {
		if (keys.revokeActiveByNode(nodeId, actorId, clock.instant()) > 0) {
			audit.record(actorId, "NODE_KEY_REVOKED", nodeId, "{\"reason\":\"node_revoked\"}");
		}
	}

	/** Chave vigente de um nó, para o painel desenhar a linha. Nunca devolve a chave em claro. */
	@Transactional(readOnly = true)
	public NodeKeyRepository.NodeKeyRecord activeKeyFor(String nodeId) {
		return keys.findActiveByNode(nodeId).orElse(null);
	}

	/** Chaves vigentes de vários nós numa consulta só (a listagem do painel). */
	@Transactional(readOnly = true)
	public Map<String, NodeKeyRepository.NodeKeyRecord> activeKeysFor(Collection<String> nodeIds) {
		return keys.findActiveByNodes(nodeIds);
	}

	/**
	 * Autentica uma requisição de nó. Devolve o nó só quando a chave confere, está vigente e o nó
	 * não está revogado — qualquer outra combinação é 401, sem dizer qual parte falhou.
	 */
	@Transactional
	public NetworkNode authenticate(String nodeId, String presentedKey) {
		if (nodeId == null || nodeId.isBlank() || presentedKey == null || presentedKey.isBlank()) {
			throw new ApiException(401, "NODE_KEY_INVALID", "Credencial de no ausente.");
		}
		Optional<NodeKeyRepository.NodeKeyRecord> match = keys.findByHash(Hashes.sha256Hex(presentedKey.trim()))
			.filter(NodeKeyRepository.NodeKeyRecord::active)
			.filter(record -> record.nodeId().equals(nodeId.trim()));
		if (match.isEmpty()) {
			throw new ApiException(401, "NODE_KEY_INVALID", "Credencial de no invalida.");
		}
		NetworkNode node = requireNode(nodeId.trim());
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(403, "NODE_REVOKED", "No revogado.");
		}
		keys.touchLastUsed(match.get().id(), clock.instant());
		return node;
	}

	private NetworkNode requireNode(String nodeId) {
		return nodes.findById(nodeId)
			.orElseThrow(() -> new ApiException(404, "NODE_UNKNOWN", "No nao encontrado."));
	}

	private String randomSecret() {
		byte[] bytes = new byte[KEY_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String shortId(String nodeId) {
		String bare = nodeId.replace("-", "").replace("_", "");
		return bare.length() <= NODE_ID_SLICE ? bare : bare.substring(0, NODE_ID_SLICE);
	}

	private static String truncate(String prefix) {
		return prefix.length() <= PREFIX_LENGTH ? prefix : prefix.substring(0, PREFIX_LENGTH);
	}

	/** A chave em claro, uma única vez. Quem recebe precisa copiar antes de fechar a tela. */
	public record IssuedKey(String keyId, String plainTextKey, String prefix) {
	}
}

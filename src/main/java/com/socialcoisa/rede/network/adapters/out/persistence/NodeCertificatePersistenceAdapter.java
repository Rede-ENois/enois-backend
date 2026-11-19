package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NodeCertificateRepository;

@Repository
class NodeCertificatePersistenceAdapter implements NodeCertificateRepository {

	private final SpringDataNodeCertificateJpaRepository repository;

	NodeCertificatePersistenceAdapter(SpringDataNodeCertificateJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public Optional<NodeCertificateRecord> findActiveByNode(String nodeId) {
		List<NodeCertificateJpaEntity> found = repository.findActiveByNode(nodeId);
		return found.isEmpty() ? Optional.empty() : Optional.of(toRecord(found.get(0)));
	}

	@Override
	public Map<String, NodeCertificateRecord> findActiveByNodes(Collection<String> nodeIds) {
		if (nodeIds == null || nodeIds.isEmpty()) {
			return Map.of();
		}
		Map<String, NodeCertificateRecord> out = new LinkedHashMap<>();
		// A consulta vem ordenada por createdAt desc: o primeiro de cada nó é o vigente.
		for (NodeCertificateJpaEntity entity : repository.findActiveByNodes(nodeIds)) {
			out.putIfAbsent(entity.nodeId(), toRecord(entity));
		}
		return out;
	}

	@Override
	public boolean serialExists(String serial) {
		return repository.existsBySerial(serial);
	}

	@Override
	public void insert(NodeCertificateRecord record) {
		if (repository.existsByFingerprintSha256(record.fingerprintSha256())) {
			// Mesmo certificado gravado duas vezes: o fingerprint é único por certificado, e repetir
			// a emissão do mesmo CSR com a mesma série geraria o mesmo DER.
			return;
		}
		repository.save(new NodeCertificateJpaEntity(record.id(), record.nodeId(), record.serial(), record.subject(),
			record.pem(), record.publicKeyBase64(), record.fingerprintSha256(), record.notBefore(),
			record.notAfter(), record.createdAt()));
	}

	@Override
	public int revokeActiveByNode(String nodeId, String actorId, String reason, Instant now) {
		return repository.revokeActiveByNode(nodeId, actorId, reason, now);
	}

	private static NodeCertificateRecord toRecord(NodeCertificateJpaEntity entity) {
		return new NodeCertificateRecord(entity.id(), entity.nodeId(), entity.serial(), entity.subject(), entity.pem(),
			entity.publicKey(), entity.fingerprintSha256(), entity.notBefore(), entity.notAfter(),
			entity.createdAt(), entity.revokedAt(), entity.revokedBy(), entity.revokedReason());
	}
}
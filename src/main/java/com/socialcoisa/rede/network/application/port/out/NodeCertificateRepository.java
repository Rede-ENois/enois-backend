package com.socialcoisa.rede.network.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Certificados X.509 individuais dos nós (brief §10).
 *
 * <p>Nunca se guarda chave privada aqui — só o certificado (público) e o estado dele. A chave
 * privada do nó vive no keystore do próprio nó, que a gerou; a da CA vive na configuração do Control
 * Plane e nunca sai de lá.</p>
 */
public interface NodeCertificateRepository {

	/** O certificado vigente (não revogado) do nó. */
	Optional<NodeCertificateRecord> findActiveByNode(String nodeId);

	/** Certificados vigentes de vários nós numa consulta só (o painel lista até 200). */
	Map<String, NodeCertificateRecord> findActiveByNodes(Collection<String> nodeIds);

	/** Verifica se um número de série já foi usado (série também não se repete). */
	boolean serialExists(String serial);

	void insert(NodeCertificateRecord record);

	/** Revoga o certificado vigente do nó; devolve 0 se não havia nenhum. */
	int revokeActiveByNode(String nodeId, String actorId, String reason, Instant now);

	record NodeCertificateRecord(String id, String nodeId, String serial, String subject, String pem,
			String publicKeyBase64, String fingerprintSha256, Instant notBefore, Instant notAfter, Instant createdAt,
			Instant revokedAt, String revokedBy, String revokedReason) {

		public boolean active() {
			return revokedAt == null;
		}

		/** Vigente cronologicamente: não revogado e dentro da validade no instante dado. */
		public boolean validAt(Instant at) {
			return active() && !at.isBefore(notBefore) && !at.isAfter(notAfter);
		}
	}
}
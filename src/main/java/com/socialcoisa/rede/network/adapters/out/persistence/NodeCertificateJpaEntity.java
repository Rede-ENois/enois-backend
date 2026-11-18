package com.socialcoisa.rede.network.adapters.out.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Linha de {@code network_node_certificates} (brief §10).
 *
 * <p><b>Por que tabela própria e não uma coluna em {@code network_node_keys}:</b> a chave de API e o
 * certificado têm ciclos de vida diferentes (o doc 04 §1 descreve as duas credenciais). A chave é
 * revogada por admin, uma por nó; o certificado é renovado pelo próprio nó a cada 90 dias e pode
 * coexistir com o anterior durante a rotação. Amarrar a rotação do certificado à revogação da chave
 * derrubaria o nó no meio de um deploy.</p>
 */
@Entity
@Table(name = "network_node_certificates")
class NodeCertificateJpaEntity {

	@Id
	@Column(length = 40)
	private String id;

	@Column(name = "node_id", nullable = false, length = 40)
	private String nodeId;

	/** Número de série em hexadecimal, sem sinal — o que o cliente vê no certificado. */
	@Column(nullable = false, length = 64)
	private String serial;

	@Column(nullable = false, length = 160)
	private String subject;

	/** O certificado inteiro em PEM. Público por definição: não há segredo aqui. */
	@Column(nullable = false, length = 4096)
	private String pem;

	/** A chave pública (SPKI DER em base64) que o certificado carrega, para diagnóstico e conferência. */
	@Column(name = "public_key", nullable = false, length = 512)
	private String publicKey;

	/** SHA-256 do DER: é o que o painel mostra e o que o operador confere. */
	@Column(name = "fingerprint_sha256", nullable = false, length = 64, unique = true)
	private String fingerprintSha256;

	@Column(name = "not_before", nullable = false)
	private Instant notBefore;

	@Column(name = "not_after", nullable = false)
	private Instant notAfter;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by", length = 40)
	private String revokedBy;

	@Column(name = "revoked_reason", length = 120)
	private String revokedReason;

	protected NodeCertificateJpaEntity() {
	}

	NodeCertificateJpaEntity(String id, String nodeId, String serial, String subject, String pem, String publicKey,
			String fingerprintSha256, Instant notBefore, Instant notAfter, Instant createdAt) {
		this.id = id;
		this.nodeId = nodeId;
		this.serial = serial;
		this.subject = subject;
		this.pem = pem;
		this.publicKey = publicKey;
		this.fingerprintSha256 = fingerprintSha256;
		this.notBefore = notBefore;
		this.notAfter = notAfter;
		this.createdAt = createdAt;
	}

	String id() {
		return id;
	}

	String nodeId() {
		return nodeId;
	}

	String serial() {
		return serial;
	}

	String subject() {
		return subject;
	}

	String pem() {
		return pem;
	}

	String publicKey() {
		return publicKey;
	}

	String fingerprintSha256() {
		return fingerprintSha256;
	}

	Instant notBefore() {
		return notBefore;
	}

	Instant notAfter() {
		return notAfter;
	}

	Instant createdAt() {
		return createdAt;
	}

	Instant revokedAt() {
		return revokedAt;
	}

	String revokedBy() {
		return revokedBy;
	}

	String revokedReason() {
		return revokedReason;
	}
}
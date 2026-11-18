package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NodeCertificateRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O certificado mTLS do nó (brief §10), do ponto de vista de quem opera: emitir a partir do CSR,
 * recusar CSR que não prova posse da chave, revogar, e recusar certificado de outro nó.
 *
 * <p>Com repositório em memória: o que se testa aqui é a <b>decisão</b> (quem recebe certificado,
 * quando o anterior cai, o que o handshake aceita), não o SQL — o SQL é do adaptador, e a migração tem
 * harness próprio em MariaDB real.</p>
 */
class NodeCertificateServiceTests {

	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

	private KeyPair ca;
	private InMemoryCertificates certificates;
	private NetworkNodeRepository nodes;
	private AuditTrail audit;
	private NodeCertificateService service;

	@BeforeEach
	void setUp() {
		ca = NodeCertificateService.newCaKeyPair();
		certificates = new InMemoryCertificates();
		nodes = mock(NetworkNodeRepository.class);
		audit = mock(AuditTrail.class);
		service = new NodeCertificateService(certificates, nodes, audit, Clock.fixed(NOW, ZoneOffset.UTC),
			base64(ca.getPrivate().getEncoded()), base64(ca.getPublic().getEncoded()));
		NetworkNode node = mock(NetworkNode.class);
		when(node.status()).thenReturn(NodeStatus.ACTIVE);
		when(nodes.findById("nd_abc")).thenReturn(Optional.of(node));
	}

	@Test
	void emite_certificado_a_partir_do_csr_e_audita() {
		var issued = service.issue("nd_abc", csr(), "u_admin");

		assertThat(issued.nodeId()).isEqualTo("nd_abc");
		assertThat(issued.certificatePem()).startsWith("-----BEGIN CERTIFICATE-----");
		assertThat(issued.caPublicKeyBase64()).isEqualTo(base64(ca.getPublic().getEncoded()));
		assertThat(certificates.active("nd_abc")).isPresent();
		verify(audit).record(any(), org.mockito.ArgumentMatchers.eq("NODE_CERTIFICATE_ISSUED"),
			org.mockito.ArgumentMatchers.eq("nd_abc"), anyString());
	}

	/** Re-emissão derruba o anterior: um nó tem um certificado vigente, como tem uma chave. */
	@Test
	void re_emissao_revoga_o_certificado_anterior() {
		service.issue("nd_abc", csr(), "u_admin");
		String primeiro = service.activeFor("nd_abc").fingerprintSha256();

		var segundo = service.issue("nd_abc", csr(), "u_admin");

		assertThat(certificates.all().get(0).revokedAt()).isEqualTo(NOW);
		assertThat(certificates.all().get(0).revokedReason()).isEqualTo("rotacao");
		assertThat(service.activeFor("nd_abc").fingerprintSha256()).isEqualTo(segundo.fingerprintSha256())
			.isNotEqualTo(primeiro);
	}

	/** A chave PRIVADA da CA nunca entra na linha gravada — só o certificado e a chave pública. */
	@Test
	void o_banco_guarda_so_o_publico() {
		service.issue("nd_abc", csr(), "u_admin");

		var record = certificates.all().get(0);
		String privada = base64(ca.getPrivate().getEncoded());
		assertThat(record.pem()).doesNotContain(privada);
		assertThat(record.publicKeyBase64()).doesNotContain(privada);
	}

	@Test
	void csr_de_outra_chave_e_recusado_sem_gravar_nada() {
		KeyPair outraChave = NodeCertificateService.newCaKeyPair();
		KeyPair chaveDoNo = NodeCertificateService.newCaKeyPair();
		String csrForjado = csrDe(chaveDoNo, outraChave);

		assertThatThrownBy(() -> service.issue("nd_abc", csrForjado, "u_admin"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("autoassinatura");
		assertThat(certificates.all()).isEmpty();
	}

	@Test
	void sem_ca_configurada_a_emissao_e_503_explicito() {
		var semCa = new NodeCertificateService(certificates, nodes, audit, Clock.fixed(NOW, ZoneOffset.UTC), "", "");

		assertThat(semCa.enabled()).isFalse();
		assertThatThrownBy(() -> semCa.issue("nd_abc", csr(), "u_admin"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("CA");
		assertThat(certificates.all()).isEmpty();
	}

	@Test
	void no_revogado_nao_recebe_certificado() {
		NetworkNode revogado = mock(NetworkNode.class);
		when(revogado.status()).thenReturn(NodeStatus.REVOKED);
		when(nodes.findById("nd_abc")).thenReturn(Optional.of(revogado));

		assertThatThrownBy(() -> service.issue("nd_abc", csr(), "u_admin"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("revogado");
		assertThat(certificates.all()).isEmpty();
	}

	@Test
	void revogar_sem_certificado_vigente_e_404_e_nao_audita() {
		assertThatThrownBy(() -> service.revoke("nd_abc", "u_admin", "comprometido"))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("nao encontrado");
		verify(audit, never()).record(any(), anyString(), anyString(), anyString());
	}

	// ------------------------------------------------------------------ helpers

	private String csr() {
		return csrDe(NodeCertificateService.newCaKeyPair(), (KeyPair) null);
	}

	/** CSR mínimo, do mesmo formato que o openssl gera, assinado pela chave do próprio par. */
	private static String csrDe(KeyPair chave, KeyPair assinadoPor) {
		try {
			KeyPair signer = assinadoPor == null ? chave : assinadoPor;
			Signature signature = NodeCertificateIssuer.signature("Ed25519", signer.getPrivate(), true);
			byte[] info = NodeCertificateTestCsr.infoDer(chave);
			signature.update(NodeCertificateTestCsr.signedBytes(info));
			return NodeCertificateTestCsr.pem(info, signature.sign());
		}
		catch (Exception falha) {
			throw new IllegalStateException(falha);
		}
	}

	private static String base64(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	/** Repositório em memória com a semântica que o adaptador JPA implementa. */
	static final class InMemoryCertificates implements NodeCertificateRepository {

		private final List<NodeCertificateRecord> rows = new ArrayList<>();

		List<NodeCertificateRecord> all() {
			return rows;
		}

		Optional<NodeCertificateRecord> active(String nodeId) {
			return rows.stream().filter(r -> r.nodeId().equals(nodeId) && r.active())
				.max(java.util.Comparator.comparing(NodeCertificateRecord::createdAt));
		}

		@Override
		public Optional<NodeCertificateRecord> findActiveByNode(String nodeId) {
			return active(nodeId);
		}

		@Override
		public Map<String, NodeCertificateRecord> findActiveByNodes(Collection<String> nodeIds) {
			Map<String, NodeCertificateRecord> out = new LinkedHashMap<>();
			for (String nodeId : nodeIds) {
				active(nodeId).ifPresent(r -> out.put(nodeId, r));
			}
			return out;
		}

		@Override
		public boolean serialExists(String serial) {
			return rows.stream().anyMatch(r -> r.serial().equals(serial));
		}

		@Override
		public void insert(NodeCertificateRecord record) {
			rows.add(record);
		}

		@Override
		public int revokeActiveByNode(String nodeId, String actorId, String reason, Instant now) {
			int changed = 0;
			for (int i = 0; i < rows.size(); i++) {
				NodeCertificateRecord r = rows.get(i);
				if (r.nodeId().equals(nodeId) && r.active()) {
					rows.set(i, new NodeCertificateRecord(r.id(), r.nodeId(), r.serial(), r.subject(), r.pem(),
						r.publicKeyBase64(), r.fingerprintSha256(), r.notBefore(), r.notAfter(), r.createdAt(), now,
						actorId, reason));
					changed++;
				}
			}
			return changed;
		}
	}
}
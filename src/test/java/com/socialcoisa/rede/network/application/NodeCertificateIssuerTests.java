package com.socialcoisa.rede.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O emissor de certificado do nó (brief §10).
 *
 * <p>O que estes testes provam, e não é pouca coisa: o certificado que sai daqui é X.509 válido para
 * o JDK, está assinado pela CA, tem o id do nó no sujeito, e a chave pública dele é a do CSR — não
 * uma chave que o cliente colou sem prova. O caso negado (CSR com autoassinatura falsa) é o que
 * separa "emissão de certificado" de "carimbo de qualquer chave pública".</p>
 */
class NodeCertificateIssuerTests {

	private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");

	@Test
	void emite_certificado_do_no_a_partir_do_csr() throws Exception {
		KeyPair ca = keyPair();
		KeyPair node = keyPair();
		String csr = csr(node, "CN=node");

		NodeCertificateIssuer.Issued issued = NodeCertificateIssuer.issue(csr, "nd_abc", ca.getPrivate(), ca.getPublic(),
			BigInteger.valueOf(42), NOW);

		var certificate = NodeCertificateIssuer.parseCertificate(Base64.getMimeDecoder()
			.decode(issued.pem().replaceAll("-----[A-Z ]+-----", "")));
		// Emitido pela CA: verificável com a chave pública dela, que é a prova de que o emissor
		// conferiu e assinou — não só montou bytes.
		certificate.verify(ca.getPublic());
		assertThat(certificate.getSubjectX500Principal().getName()).isEqualTo("CN=ENOIS-NODE-nd_abc");
		assertThat(certificate.getPublicKey().getEncoded()).isEqualTo(node.getPublic().getEncoded());
		assertThat(certificate.getNotAfter().toInstant())
			.isAfter(NOW.plus(NodeCertificateIssuer.VALIDITY).minusSeconds(1));
		assertThat(certificate.getBasicConstraints()).isEqualTo(-1);
		assertThat(issued.publicKeyBase64()).isEqualTo(
			Base64.getEncoder().encodeToString(node.getPublic().getEncoded()));
	}

	/**
	 * O CSR com autoassinatura que não confere é recusado.
	 *
	 * <p>Mutação discriminante: sem a verificação no emissor, este CSR geraria um certificado
	 * perfeitamente válido para uma chave cuja chave privada o remetente **não provou ter**.</p>
	 */
	@Test
	void csr_com_autoassinatura_falsa_e_recusado() throws Exception {
		KeyPair ca = keyPair();
		KeyPair node = keyPair();
		String forged = forgedCsr(node, "CN=node");

		assertThatThrownBy(() -> NodeCertificateIssuer.issue(forged, "nd_abc", ca.getPrivate(), ca.getPublic(),
			BigInteger.ONE, NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("autoassinatura");
	}

	/** Só Ed25519: CSR RSA seria aceito por qualquer CA, mas o keystore do nó é Ed25519. */
	@Test
	void csr_com_algoritmo_diferente_de_ed25519_e_recusado() throws Exception {
		KeyPair ca = keyPair();
		KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
		String csr = csr(rsa);

		assertThatThrownBy(() -> NodeCertificateIssuer.issue(csr, "nd_abc", ca.getPrivate(), ca.getPublic(),
			BigInteger.ONE, NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("Ed25519");
	}

	@Test
	void csr_vazio_ou_ilegivel_e_400() {
		KeyPair ca = keyPairQuietly();
		assertThatThrownBy(() -> NodeCertificateIssuer.issue("", "nd_abc", ca.getPrivate(), ca.getPublic(), BigInteger.ONE, NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("ausente");
		assertThatThrownBy(() -> NodeCertificateIssuer.issue("nao e um csr", "nd_abc", ca.getPrivate(), ca.getPublic(),
			BigInteger.ONE, NOW))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("base64");
	}

	// ------------------------------------------------------------------ helpers: um "openssl req"

	private static KeyPair keyPair() throws Exception {
		return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
	}

	private static KeyPair keyPairQuietly() {
		try {
			return keyPair();
		}
		catch (Exception falha) {
			throw new IllegalStateException(falha);
		}
	}

	/** CSR como o openssl gera: CertificationRequest com info, algoritmo e assinatura. */
	private static String csr(KeyPair pair) {
		return csr(pair, "CN=node", false);
	}

	private static String csr(KeyPair pair, String subject) {
		return csr(pair, subject, false);
	}

	private static String forgedCsr(KeyPair pair, String subject) {
		return csr(pair, subject, true);
	}

	private static String csr(KeyPair pair, String subject, boolean forge) {
		try {
			// O JDK chama o algoritmo de EdDSA (não de Ed25519): o OID do CSR é Ed25519, e é o que o emissor exige.
			boolean ed = "EdDSA".equalsIgnoreCase(pair.getPublic().getAlgorithm())
				|| "Ed25519".equalsIgnoreCase(pair.getPublic().getAlgorithm());
			AlgorithmIdentifier alg = ed ? new AlgorithmIdentifier(new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.3.101.112"))
				: new AlgorithmIdentifier(new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113549.1.1.11"), DERNull.INSTANCE);
			SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded());
			ASN1EncodableVector info = new ASN1EncodableVector();
			info.add(new org.bouncycastle.asn1.ASN1Integer(0));
			info.add(new X500Name(subject));
			info.add(spki);
			info.add(new DERTaggedObject(true, 0, new DERSet()));
			byte[] infoDer = new DERSequence(info).getEncoded("DER");

			ASN1EncodableVector signedVector = new ASN1EncodableVector();
			signedVector.add(new DERSequence(info));
			signedVector.add(alg);
			byte[] signed = new DERSequence(signedVector).getEncoded("DER");

			byte[] signature;
			if (forge) {
				// Assinatura de OUTRA chave: o CSR é sintaticamente perfeito e a chave pública
				// alegada é a do nó, mas a prova de posse é falsa.
				KeyPair other = keyPair();
				Signature signer = signatureFor(other.getPrivate());
				signer.initSign(other.getPrivate());
				signer.update(signed);
				signature = signer.sign();
			}
			else {
				Signature signer = signatureFor(pair.getPrivate());
				signer.initSign(pair.getPrivate());
				signer.update(signed);
				signature = signer.sign();
			}

			ASN1EncodableVector request = new ASN1EncodableVector();
			request.add(new DERSequence(info));
			request.add(alg);
			request.add(new org.bouncycastle.asn1.DERBitString(signature));
			String der = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(new DERSequence(request).getEncoded("DER"));
			StringWriter pem = new StringWriter();
			pem.write("-----BEGIN CERTIFICATE REQUEST-----\n" + der + "\n-----END CERTIFICATE REQUEST-----\n");
			// infoDer só existe para garantir que a codificação canônica foi exercitada
			assertThat(infoDer).isNotEmpty();
			return pem.toString();
		}
		catch (Exception falha) {
			throw new IllegalStateException(falha);
		}
	}

	/**
	 * Assina como o openssl assinaria o CSR. O emissor descobre o provider da chave por conta
	 * própria; aqui o helper faz o mesmo porque o teste também precisa assinar.
	 */
	private static Signature signatureFor(java.security.Key key) throws Exception {
		if ("RSA".equalsIgnoreCase(key.getAlgorithm())) {
			Signature rsa = Signature.getInstance("SHA256withRSA");
			rsa.initSign((PrivateKey) key);
			return rsa;
		}
		return NodeCertificateIssuer.signature("Ed25519", key, true);
	}

	/** A chave privada da CA nunca chega ao corpo da resposta nem ao banco. */
	@Test
	void a_resposta_nao_leva_chave_privada() throws Exception {
		KeyPair ca = keyPair();
		PrivateKey caKey = ca.getPrivate();
		var issued = NodeCertificateIssuer.issue(csr(keyPair()), "nd_abc", caKey, ca.getPublic(), BigInteger.TEN, NOW);
		String privatePart = Base64.getEncoder().encodeToString(caKey.getEncoded());
		assertThat(issued.toString()).doesNotContain(privatePart);
	}
}
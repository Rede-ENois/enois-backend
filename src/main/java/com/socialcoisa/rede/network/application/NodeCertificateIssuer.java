package com.socialcoisa.rede.network.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.bouncycastle.asn1.ASN1BitString;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Emissão de certificado X.509 individual de nó a partir de um CSR (brief §10: "mTLS",
 * "certificados individuais", "CSR no enroll").
 *
 * <p><b>Por que a classe existe:</b> o Control Plane precisa emitir certificado sem confiar no
 * cliente. O CSR traz a chave pública e é autoassinado — qualquer um gera um. O que o torna confiável
 * é <b>o Control Plane conferir a autoassinatura antes de copiar a chave pública para dentro de um
 * certificado com o nome do nó</b>: sem isso, qualquer pessoa emite certificado válido para uma chave
 * que não possui (doc 04 §8: "CSR no enroll, assinado pelo Control Plane").</p>
 *
 * <p><b>Nenhuma chave privada do nó aqui.</b> O nó gera o par, envia o CSR e recebe o certificado. A
 * chave do emissor (a CA da rede) é a única privada neste lado, vem da configuração e nunca é
 * distribuída (brief §10: "Não distribuir chaves mestras aos participantes").</p>
 *
 * <p><b>DER escrito com as primitivas ASN.1 do BouncyCastle</b> ({@code bcprov}, já em runtime pelo
 * web-push). Nenhum campo vindo do cliente entra no certificado em texto: nome, validade, série e
 * sujeito são construídos aqui. O único campo herdado do CSR é a chave pública — e só depois da
 * verificação. A assinatura é do JDK, sobre o {@code TBSCertificate} já codificado: exatamente os
 * bytes que compõem o certificado.</p>
 */
public final class NodeCertificateIssuer {

	/** Oid de Ed25519 ({@code id-Ed25519}, RFC 8410) — o único algoritmo aceito. */
	private static final ASN1ObjectIdentifier ED25519 = new ASN1ObjectIdentifier("1.3.101.112");

	/** Nome do emissor: a CA da rede, fixo e não configurável pelo cliente. */
	public static final String CA_NAME = "CN=ENOIS-NETWORK-CA";

	/** O CN é o id do nó — o campo que o painel e a auditoria leem. */
	static final String SUBJECT_PREFIX = "CN=ENOIS-NODE-";

	/** Validade curta de propósito: certificado de nó é rotacionado, não é "para sempre". */
	static final Duration VALIDITY = Duration.ofDays(90);

	/**
	 * Margem para trás na {@code notBefore}: relógio de máquina doméstica atrasado é comum e gerava
	 * recusa de "certificado ainda não válido" no handshake.
	 */
	private static final Duration BACKDATE = Duration.ofMinutes(5);

	/** Limite do CSR. Um CSR Ed25519 tem ~150–400 bytes; 1 MB é abuso, não pedido. */
	private static final int MAX_CSR_BYTES = 4096;

	private NodeCertificateIssuer() {
	}

	/** Resultado da emissão: o certificado em PEM e os campos que a linha do banco guarda. */
	record Issued(String pem, String serial, String subject, String publicKeyBase64, Instant notBefore,
			Instant notAfter) {
	}

	/**
	 * Emite o certificado do nó a partir do CSR.
	 *
	 * @param csr o CSR em PEM ({@code BEGIN CERTIFICATE REQUEST}) ou DER em base64
	 * @param nodeId o id do nó; vira o CN — o CSR não escolhe o próprio nome
	 * @param caKey chave privada da CA da rede (Ed25519)
	 * @param caPublic a chave pública dela, usada para CONFERIR o certificado antes de entregá-lo
	 * @param serial número de série, gerado pelo chamador (positivo e único)
	 * @param now instante da emissão
	 */
	static Issued issue(String csr, String nodeId, PrivateKey caKey, PublicKey caPublic, BigInteger serial,
			Instant now) {
		if (nodeId == null || nodeId.isBlank()) {
			throw new ApiException(400, "NODE_CSR_INVALID", "No sem id para emitir certificado.");
		}
		ASN1Sequence request = parseCsr(csr);
		ASN1Sequence info = ASN1Sequence.getInstance(request.getObjectAt(0));
		ASN1Sequence signatureAlgorithm = ASN1Sequence.getInstance(request.getObjectAt(1));
		ASN1BitString signatureBits = ASN1BitString.getInstance(request.getObjectAt(2));

		String algorithm = signatureAlgorithm.getObjectAt(0).toString();
		if (!ED25519.getId().equals(algorithm)) {
			// Só Ed25519: é o algoritmo da config assinada e o de chave curta no keystore do nó.
			throw new ApiException(400, "NODE_CSR_ALGORITHM", "O CSR precisa usar Ed25519; recebido " + algorithm + ".");
		}
		SubjectPublicKeyInfo subjectKey = SubjectPublicKeyInfo.getInstance(info.getObjectAt(2));
		verifySelfSignature(subjectKey, info, signatureAlgorithm, signatureBits);

		String publicKeyBase64 = Base64.getEncoder().encodeToString(der(subjectKey));
		Instant notBefore = now.minus(BACKDATE);
		Instant notAfter = now.plus(VALIDITY);
		X500Name subject = new X500Name(SUBJECT_PREFIX + nodeId);

		// TBSCertificate: v3, série, algoritmo, emissor, validade, sujeito, chave pública, extensões.
		ASN1EncodableVector tbs = new ASN1EncodableVector();
		tbs.add(new DERTaggedObject(true, 0, new ASN1Integer(2)));
		tbs.add(new ASN1Integer(serial));
		tbs.add(algorithm());
		tbs.add(new X500Name(CA_NAME));
		ASN1EncodableVector validity = new ASN1EncodableVector();
		validity.add(new Time(Date.from(notBefore)));
		validity.add(new Time(Date.from(notAfter)));
		tbs.add(new DERSequence(validity));
		tbs.add(subject);
		tbs.add(subjectKey);
		tbs.add(new DERTaggedObject(true, 3, extensions()));

		byte[] tbsDer = der(new DERSequence(tbs));
		byte[] signatureBytes = sign(caKey, tbsDer);
		ASN1EncodableVector certificate = new ASN1EncodableVector();
		certificate.add(ASN1Sequence.getInstance(tbsDer));
		certificate.add(algorithm());
		certificate.add(new DERBitString(signatureBytes));
		byte[] certificateDer = der(new DERSequence(certificate));

		// Confere com o próprio JDK antes de entregar: certificado malformado que sai daqui só
		// falharia no handshake do nó, longe da causa.
		X509Certificate issued = parseCertificate(certificateDer);
		try {
			issued.verify(caPublic);
		}
		catch (GeneralSecurityException | IllegalArgumentException falha) {
			throw new IllegalStateException("certificado emitido nao confere com a chave da CA", falha);
		}
		if (!subjectDoCertificado(issued).equals(nodeId)) {
			throw new IllegalStateException("certificado emitido com sujeito inesperado: " + issued.getSubjectX500Principal());
		}
		try {
			issued.checkValidity(Date.from(now));
		}
		catch (GeneralSecurityException falha) {
			throw new IllegalStateException("certificado emitido fora da validade", falha);
		}
		return new Issued(pem("CERTIFICATE", certificateDer), serial.toString(16), issued.getSubjectX500Principal().getName(),
			publicKeyBase64, notBefore, notAfter);
	}

	/**
	 * Confere a autoassinatura do CSR com a chave pública QUE ELE ALEGA.
	 *
	 * <p>É a única prova de posse do CSR: prova que quem enviou o pedido tem a chave privada
	 * correspondente a esta pública. Sem esta verificação, o emissor estaria carimbando qualquer
	 * chave pública que chegasse — e é exatamente esse certificado que o nó usaria no mTLS.</p>
	 */
	private static void verifySelfSignature(SubjectPublicKeyInfo subjectKey, ASN1Sequence info,
			ASN1Sequence signatureAlgorithm, ASN1BitString signatureBits) {
		try {
			PublicKey publicKey = KeyFactory.getInstance("Ed25519")
				.generatePublic(new X509EncodedKeySpec(der(subjectKey)));
			byte[] signed = der(new DERSequence(new org.bouncycastle.asn1.ASN1Encodable[] { info, signatureAlgorithm }));
			Signature verifier = signature("Ed25519", publicKey, false);
			verifier.update(signed);
			if (!verifier.verify(signatureBits.getOctets())) {
				throw new ApiException(400, "NODE_CSR_SIGNATURE", "A autoassinatura do CSR nao confere.");
			}
		}
		catch (GeneralSecurityException falha) {
			throw new ApiException(400, "NODE_CSR_INVALID", "CSR invalido: chave do CSR ilegivel.");
		}
	}

	/**
	 * {@code Signature} instanciado pelo provider que aceita a chave — e <b>já inicializado com ela</b>.
	 *
	 * <p><b>Por que não {@code Signature.getInstance("Ed25519")} e pronto:</b> o JDK nomeia as chaves
	 * Ed25519 como {@code EdDSA} ({@code EdECKey#getAlgorithm()}), e a escolha de provider segue a
	 * {@code KeyPairGenerator}: instalar o BouncyCastle (o web-push já faz) muda qual provider detém
	 * Ed25519 no processo. Com chave de um provider e {@code Signature} resolvido no outro, o JDK
	 * responde "No installed provider supports this key". A regra aqui é: <b>quem aceita a chave
	 * decide</b>, e o objeto devolvido já foi inicializado — re-inicializar não é garantido e é o que
	 * deixou um estado estranho na primeira versão.</p>
	 */
	public static Signature signature(String algorithm, java.security.Key key, boolean forSigning) {
		boolean privateKey = key instanceof PrivateKey;
		if (forSigning != privateKey) {
			throw new IllegalStateException("chave de tipo inesperado para " + (forSigning ? "assinar" : "verificar"));
		}
		for (Provider candidate : Security.getProviders()) {
			if (candidate.getService("Signature", algorithm) == null) {
				continue;
			}
			try {
				Signature signature = Signature.getInstance(algorithm, candidate);
				if (forSigning) {
					signature.initSign((PrivateKey) key);
				}
				else {
					signature.initVerify((PublicKey) key);
				}
				return signature;
			}
			catch (GeneralSecurityException | RuntimeException ignored) {
				// este provider não aceita a chave: tenta o próximo
			}
		}
		// Último recurso: escolha padrão do JCA, sem provider explícito.
		try {
			Signature signature = Signature.getInstance(algorithm);
			if (forSigning) {
				signature.initSign((PrivateKey) key);
			}
			else {
				signature.initVerify((PublicKey) key);
			}
			return signature;
		}
		catch (GeneralSecurityException falha) {
			throw new IllegalStateException("assinatura " + algorithm + " indisponivel", falha);
		}
	}

	private static byte[] sign(PrivateKey caKey, byte[] data) {
		try {
			Signature signer = signature("Ed25519", caKey, true);
			signer.update(data);
			return signer.sign();
		}
		catch (GeneralSecurityException falha) {
			throw new IllegalStateException("falha ao assinar o certificado do no", falha);
		}
	}

	private static AlgorithmIdentifier algorithm() {
		return new AlgorithmIdentifier(ED25519);
	}

	/**
	 * Extensões do certificado do nó: {@code basicConstraints CA:false} (nó não emite certificado) e
	 * {@code extendedKeyUsage} serverAuth+clientAuth (o nó é cliente e servidor no mTLS).
	 */
	private static ASN1Sequence extensions() {
		ASN1EncodableVector list = new ASN1EncodableVector();
		list.add(new Extension(Extension.basicConstraints, true, octets(new BasicConstraints(false))));
		list.add(new Extension(Extension.extendedKeyUsage, false,
			octets(new ExtendedKeyUsage(new KeyPurposeId[] { KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth }))));
		return ASN1Sequence.getInstance(der(new DERSequence(list)));
	}

	private static DEROctetString octets(org.bouncycastle.asn1.ASN1Encodable value) {
		return new DEROctetString(der(value));
	}

	/** O id do nó dentro do CN do sujeito emitido. */
	private static String subjectDoCertificado(X509Certificate certificate) {
		return certificate.getSubjectX500Principal().getName().replace(SUBJECT_PREFIX, "").trim();
	}

	static X509Certificate parseCertificate(byte[] der) {
		try {
			return (X509Certificate) CertificateFactory.getInstance("X.509")
				.generateCertificate(new ByteArrayInputStream(der));
		}
		catch (GeneralSecurityException falha) {
			throw new IllegalStateException("certificado X.509 invalido", falha);
		}
	}

	/**
	 * Lê o CSR: PEM com cabeçalho {@code CERTIFICATE REQUEST} (o que {@code openssl req} gera) ou o
	 * DER em base64 sem cabeçalho. Qualquer outra coisa é 400 — não há CSR "quase certo".
	 */
	static ASN1Sequence parseCsr(String csr) {
		if (csr == null || csr.isBlank()) {
			throw new ApiException(400, "NODE_CSR_MISSING", "CSR ausente no enroll.");
		}
		byte[] bytes = pemOrBase64(csr);
		if (bytes.length > MAX_CSR_BYTES) {
			throw new ApiException(400, "NODE_CSR_TOO_LARGE", "CSR maior que " + MAX_CSR_BYTES + " bytes.");
		}
		try {
			ASN1Sequence sequence = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(bytes));
			if (sequence == null || sequence.size() != 3) {
				throw new ApiException(400, "NODE_CSR_INVALID", "CSR com estrutura inesperada.");
			}
			return sequence;
		}
		catch (IOException | IllegalArgumentException falha) {
			throw new ApiException(400, "NODE_CSR_INVALID", "CSR ilegivel.");
		}
	}

	private static byte[] pemOrBase64(String value) {
		String body = value.replace("\r", "").trim();
		if (body.startsWith("-----BEGIN")) {
			int start = body.indexOf('\n');
			int end = body.indexOf("-----END");
			if (start < 0 || end < 0 || end <= start) {
				throw new ApiException(400, "NODE_CSR_INVALID", "PEM do CSR malformado.");
			}
			body = body.substring(start + 1, end);
		}
		try {
			return Base64.getMimeDecoder().decode(body);
		}
		catch (IllegalArgumentException falha) {
			throw new ApiException(400, "NODE_CSR_INVALID", "CSR nao e base64 valido.");
		}
	}

	/** Codificação DER canônica: o JDK só aceita DER exatamente neste formato. */
	private static byte[] der(org.bouncycastle.asn1.ASN1Encodable encodable) {
		try {
			return encodable.toASN1Primitive().getEncoded(ASN1Encoding.DER);
		}
		catch (IOException falha) {
			throw new IllegalStateException("falha ao codificar DER", falha);
		}
	}

	private static String pem(String type, byte[] der) {
		String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
		return "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n";
	}
}
package com.socialcoisa.rede.network.application;

import java.security.KeyPair;
import java.util.Base64;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;

/**
 * Gerador de CSR de teste no formato do {@code openssl req} — usado pelos testes do §10.
 *
 * <p>Não é código de produção: monta {@code CertificationRequest} (info, algoritmo, assinatura) a
 * partir de um par Ed25519. Existe para que os testes provem o que o emissor precisa provar — que ele
 * confere a autoassinatura — com um CSR cuja assinatura pode ser feita pela chave certa ou por uma
 * chave qualquer.</p>
 */
public final class NodeCertificateTestCsr {

	static final ASN1ObjectIdentifier ED25519 = new ASN1ObjectIdentifier("1.3.101.112");

	private NodeCertificateTestCsr() {
	}

	/** O {@code CertificationRequestInfo} codificado — o que a assinatura do CSR cobre, junto do algoritmo. */
	public static byte[] infoDer(KeyPair pair) {
		try {
			ASN1EncodableVector info = new ASN1EncodableVector();
			info.add(new ASN1Integer(0));
			info.add(new X500Name("CN=node"));
			info.add(SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()));
			info.add(new DERTaggedObject(true, 0, new DERSet()));
			return new DERSequence(info).getEncoded(ASN1Encoding.DER);
		}
		catch (Exception falha) {
			throw new IllegalStateException(falha);
		}
	}

	/** Os bytes que a assinatura do CSR cobre: a SEQUÊNCIA de (info, algoritmo). */
	public static byte[] signedBytes(byte[] infoDer) {
		try {
			ASN1EncodableVector signed = new ASN1EncodableVector();
			signed.add(org.bouncycastle.asn1.ASN1Sequence.getInstance(infoDer));
			signed.add(new AlgorithmIdentifier(ED25519));
			return new DERSequence(signed).getEncoded(ASN1Encoding.DER);
		}
		catch (Exception falha) {
			throw new IllegalStateException(falha);
		}
	}

	/** O CSR em PEM a partir do info e da assinatura. */
	public static String pem(byte[] infoDer, byte[] signature) {
		try {
			ASN1EncodableVector request = new ASN1EncodableVector();
			request.add(org.bouncycastle.asn1.ASN1Sequence.getInstance(infoDer));
			request.add(new AlgorithmIdentifier(ED25519));
			request.add(new DERBitString(signature));
			String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(new DERSequence(request)
				.getEncoded(ASN1Encoding.DER));
			return "-----BEGIN CERTIFICATE REQUEST-----\n" + body + "\n-----END CERTIFICATE REQUEST-----\n";
		}
		catch (Exception falha) {
			throw new IllegalStateException(falha);
		}
	}
}
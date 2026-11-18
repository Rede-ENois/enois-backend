package com.socialcoisa.rede.network.application;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.NodeCertificateRepository;
import com.socialcoisa.rede.network.application.port.out.NodeCertificateRepository.NodeCertificateRecord;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Emissão, renovação e revogação do certificado mTLS de cada nó (brief §10, doc 04 §1).
 *
 * <p><b>Duas credenciais, dois papéis.</b> O certificado prova "esta conexão vem deste nó" e protege
 * o canal; a API key ({@link NodeKeyService}) prova "este nó está autorizado a operar" e é o que o
 * admin revoga. Perder uma não basta para operar — e este serviço não substitui a outra: ele só
 * acrescenta a que faltava.</p>
 *
 * <p><b>A chave da CA vive na configuração, nunca no banco.</b> Sem isso, quem copia o disco do
 * Control Plane forja certificado de qualquer nó (brief §10: "Considerar que operadores podem acessar
 * fisicamente os discos", "Não distribuir chaves mestras"). Sem a chave configurada, o serviço diz
 * {@code enabled=false} e a emissão responde 503 explícito — em vez de gerar uma CA em repouso, que
 * seria uma segunda autoridade escondida.</p>
 */
@Service
public class NodeCertificateService {

	/**
	 * Chave PRIVADA da CA (PKCS#8 DER em base64). Vive na configuração do Control Plane
	 * ({@code REDE_NETWORK_CA_KEY}), nunca no banco.
	 */
	static final String CA_KEY_PROPERTY = "rede.network.ca-key";

	/**
	 * Chave PÚBLICA da CA (SPKI DER em base64), {@code REDE_NETWORK_CA_PUBLIC}.
	 *
	 * <p>Separada da privada de propósito: quem só precisa CONFERIR certificado (o handshake, a
	 * auditoria, um nó de verificação) recebe só a pública. A privada fica onde só o emissor a lê.</p>
	 */
	static final String CA_PUBLIC_PROPERTY = "rede.network.ca-public";

	/**
	 * Nome reconhecível da CA, para o painel. Precisa bater com {@link NodeCertificateIssuer#CA_NAME}:
	 * o certificado é conferido contra ela.
	 */
	public static final String CA_SUBJECT = NodeCertificateIssuer.CA_NAME;

	private final SecureRandom random = new SecureRandom();

	private final NodeCertificateRepository certificates;
	private final NetworkNodeRepository nodes;
	private final AuditTrail audit;
	private final Clock clock;
	private final PrivateKey caKey;
	private final PublicKey caPublic;

	public NodeCertificateService(NodeCertificateRepository certificates, NetworkNodeRepository nodes, AuditTrail audit,
			Clock clock, @Value("${" + CA_KEY_PROPERTY + ":}") String caKeyBase64,
			@Value("${" + CA_PUBLIC_PROPERTY + ":}") String caPublicBase64) {
		this.certificates = certificates;
		this.nodes = nodes;
		this.audit = audit;
		this.clock = clock;
		this.caKey = StringUtils.hasText(caKeyBase64) ? chavePrivada(caKeyBase64) : null;
		this.caPublic = StringUtils.hasText(caPublicBase64) ? chavePublica(caPublicBase64) : null;
		if (caKey != null && caPublic == null) {
			// Emitir certificado que não se consegue conferir depois é pior que não emitir: o
			// handshake recusaria todos os nós e o motivo apareceria longe, na borda.
			throw new IllegalStateException(
				"REDE_NETWORK_CA_PUBLIC ausente: sem a chave publica da CA o certificado emitido nao pode ser conferido.");
		}
	}

	private static PrivateKey chavePrivada(String base64) {
		try {
			return java.security.KeyFactory.getInstance("Ed25519")
				.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
		}
		catch (GeneralSecurityException | IllegalArgumentException falha) {
			throw new IllegalStateException(
				"REDE_NETWORK_CA_KEY invalida: esperado PKCS#8 Ed25519 em base64 (" + CA_KEY_PROPERTY + ").", falha);
		}
	}

	private static PublicKey chavePublica(String base64) {
		try {
			return java.security.KeyFactory.getInstance("Ed25519")
				.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
		}
		catch (GeneralSecurityException | IllegalArgumentException falha) {
			throw new IllegalStateException(
				"REDE_NETWORK_CA_PUBLIC invalida: esperado SPKI Ed25519 em base64 (" + CA_PUBLIC_PROPERTY + ").", falha);
		}
	}

	/**
	 * O que o enrolamento devolve ao nó: o certificado emitido e a chave pública da CA, para o nó
	 * conferir que o certificado foi mesmo assinado por ela (mesmo princípio do pino da config,
	 * doc 04 §6.1 — o campo viaja pela rede, então serve de conferência, não de autoridade).
	 */
	public record IssuedCertificate(String nodeId, String certificatePem, String caPublicKeyBase64, String serial,
			String fingerprintSha256, String notBefore, String notAfter) {
	}

	/** Estado do certificado vigente, para o painel. Nunca devolve chave. */
	public record CertificateView(String serial, String subject, String fingerprintSha256, String notBefore,
			String notAfter, boolean expired, float daysRemaining) {

		public static CertificateView of(NodeCertificateRecord record, Instant now) {
			float days = (record.notAfter().toEpochMilli() - now.toEpochMilli()) / 86_400_000f;
			return new CertificateView(record.serial(), record.subject(), record.fingerprintSha256(),
				record.notBefore().toString(), record.notAfter().toString(), record.notAfter().isBefore(now), days);
		}
	}

	/** Se a CA está configurada. Sem ela a emissão falha explícita — não há CA gerada em repouso. */
	public boolean enabled() {
		return caKey != null;
	}

	/**
	 * Emite o certificado do nó a partir do CSR.
	 *
	 * <p>Re-emitir revoga o anterior na mesma transação: um nó tem um certificado vigente, como tem
	 * uma chave vigente. A janela de dois válidos durante o deploy é do lado do cliente (ele guarda o
	 * anterior até o último handshake), não do servidor.</p>
	 */
	@Transactional
	public IssuedCertificate issue(String nodeId, String csrPem, String actorId) {
		if (!enabled()) {
			throw new ApiException(503, "NODE_CA_UNAVAILABLE",
				"A CA do Control Plane nao esta configurada; emissao de certificado indisponivel.");
		}
		NetworkNode node = requireNode(nodeId);
		if (node.status() == NodeStatus.REVOKED) {
			throw new ApiException(409, "NODE_REVOKED", "No revogado nao recebe certificado novo.");
		}
		Instant now = clock.instant();
		BigInteger serial = nextSerial();
		NodeCertificateIssuer.Issued issued = NodeCertificateIssuer.issue(csrPem, nodeId, caKey, caPublic, serial, now);
		byte[] der = derDoPem(issued.pem());
		String fingerprint = sha256Hex(der);

		certificates.revokeActiveByNode(nodeId, actorId, "rotacao", now);
		certificates.insert(new NodeCertificateRecord(NodeIds.next("nct"), nodeId, issued.serial(), issued.subject(),
			issued.pem(), issued.publicKeyBase64(), fingerprint, issued.notBefore(), issued.notAfter(), now, null, null,
			null));
		audit.record(actorId, "NODE_CERTIFICATE_ISSUED", nodeId,
			"{\"serial\":\"" + issued.serial() + "\",\"fingerprint\":\"" + fingerprint + "\"}");
		return new IssuedCertificate(nodeId, issued.pem(), Base64.getEncoder().encodeToString(caPublic.getEncoded()),
			issued.serial(), fingerprint, issued.notBefore().toString(), issued.notAfter().toString());
	}

	/**
	 * Revoga o certificado vigente do nó. Diferente da revogação da API key: aqui o nó continua com a
	 * key, mas o canal TLS deixa de reconhecê-lo no próximo handshake — o que só tem efeito quando a
	 * terminação TLS estiver ligada (ver doc 10 §4).
	 */
	@Transactional
	public void revoke(String nodeId, String actorId, String reason) {
		requireNode(nodeId);
		if (certificates.revokeActiveByNode(nodeId, actorId, reason == null ? "revogacao" : reason, clock.instant()) == 0) {
			throw new ApiException(404, "NODE_CERTIFICATE_NOT_FOUND", "Certificado vigente nao encontrado.");
		}
		audit.record(actorId, "NODE_CERTIFICATE_REVOKED", nodeId, "{\"reason\":\"" + (reason == null ? "" : reason) + "\"}");
	}

	/** Revoga tudo do nó (usado quando o nó é revogado). Não falha sem certificado vigente. */
	@Transactional
	public void revokeAllForNode(String nodeId, String actorId) {
		if (certificates.revokeActiveByNode(nodeId, actorId, "no revogado", clock.instant()) > 0) {
			audit.record(actorId, "NODE_CERTIFICATE_REVOKED", nodeId, "{\"reason\":\"node_revoked\"}");
		}
	}

	@Transactional(readOnly = true)
	public CertificateView activeFor(String nodeId) {
		return certificates.findActiveByNode(nodeId)
			.map(record -> CertificateView.of(record, clock.instant()))
			.orElse(null);
	}

	@Transactional(readOnly = true)
	public Map<String, CertificateView> activeFor(Collection<String> nodeIds) {
		Instant now = clock.instant();
		return certificates.findActiveByNodes(nodeIds).entrySet()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> CertificateView.of(e.getValue(), now)));
	}

	/**
	 * Confere um certificado apresentado no handshake TLS: assinado pela CA, emitido para este nó,
	 * vigente e não revogado.
	 *
	 * <p>É o que faltava no doc 04 §6, onde a linha do handshake prometia "certificado válido e não
	 * revogado" quando não havia handshake com certificado. A decisão aqui é a única que importa:
	 * certificado de outro nó é recusado mesmo que seja legítimo — o CN é comparado com o nó e o
	 * registro tem de existir como ativo.</p>
	 */
	@Transactional(readOnly = true)
	public boolean accepts(String nodeId, java.security.cert.X509Certificate presented) {
		if (!enabled() || nodeId == null || presented == null) {
			return false;
		}
		try {
			presented.verify(caPublic);
		}
		catch (GeneralSecurityException falha) {
			return false;
		}
		// O ESTADO DO NÓ VEM ANTES DO CERTIFICADO. O certificado prova "quem é"; quem decide "pode
		// entrar" é o estado do autorizador. Sem esta checagem, revogar o nó matava a API key e
		// deixava o certificado vigente — e o handshake aceitaria de volta, por TLS, o nó que o admin
		// tinha derrubado. A perda de potência tem de valer nos dois canais, não só no da API key.
		Optional<NetworkNode> node = nodes.findById(nodeId);
		if (node.isEmpty() || !node.get().status().canOperate()) {
			// canOperate() é o mesmo critério do despacho de tarefa: só ACTIVE opera. SUSPENDED,
			// QUARANTINED, PAUSED, PENDING_APPROVAL e REVOKED ficam de fora — pausar um nó pela tela
			// do participante tem de tirar também o canal TLS, senão a pausa é só no painel.
			return false;
		}
		Optional<NodeCertificateRecord> active = certificates.findActiveByNode(nodeId);
		if (active.isEmpty()) {
			return false;
		}
		NodeCertificateRecord record = active.get();
		if (!record.validAt(clock.instant())) {
			// Revogado (janela de rotação) ou fora da validade: não é credencial.
			return false;
		}
		return sha256Hex(derDoPem(record.pem())).equals(sha256Hex(digest(presented)));
	}

	/**
	 * A chave pública da CA em base64 — o que o nó fixa (pino) e usa para conferir o próprio
	 * certificado recebido.
	 *
	 * <p>Não é "certificado da CA": a CA não tem certificado X.509 próprio. A raiz é a chave; inventar
	 * um certificado autoassinado seria distribuir um segundo nome para a mesma raiz.</p>
	 */
	public String caPublicKeyBase64() {
		return caPublic == null ? null : Base64.getEncoder().encodeToString(caPublic.getEncoded());
	}

	private NetworkNode requireNode(String nodeId) {
		if (nodeId == null || nodeId.isBlank()) {
			throw new ApiException(400, "NODE_UNKNOWN", "No nao informado.");
		}
		return nodes.findById(nodeId)
			.orElseThrow(() -> new ApiException(404, "NODE_UNKNOWN", "No nao encontrado."));
	}

	/** Série positiva e não repetida: 128 bits aleatórios, com o bit de sinal zerado. */
	private BigInteger nextSerial() {
		for (int attempt = 0; attempt < 5; attempt++) {
			byte[] bytes = new byte[16];
			random.nextBytes(bytes);
			bytes[0] &= 0x7F;
			BigInteger serial = new BigInteger(1, bytes);
			if (serial.signum() > 0 && !certificates.serialExists(serial.toString(16))) {
				return serial;
			}
		}
		throw new IllegalStateException("nao foi possivel gerar numero de serie de certificado unico");
	}

	private static byte[] digest(java.security.cert.X509Certificate certificate) {
		try {
			return certificate.getEncoded();
		}
		catch (GeneralSecurityException falha) {
			throw new IllegalStateException("certificado sem codificacao DER", falha);
		}
	}

	/**
	 * SHA-256 em hexadecimal sobre os BYTES do DER.
	 *
	 * <p>{@code Hashes.sha256Hex} recebe String porque o projeto o usa para token e segredo; calcular
	 * sobre a string de um DER binário em UTF-8 destruiria bytes e daria fingerprint que não confere
	 * com o do {@code openssl x509 -fingerprint}.</p>
	 */
	static String sha256Hex(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (GeneralSecurityException falha) {
			throw new IllegalStateException("SHA-256 indisponivel", falha);
		}
	}

	/** O DER de um PEM, sem cabeçalho. */
	static byte[] derDoPem(String pem) {
		try {
			return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
		}
		catch (IllegalArgumentException falha) {
			throw new IllegalStateException("PEM de certificado ilegivel", falha);
		}
	}

	/**
	 * Gera um par Ed25519 — usado pelo teste e pelo comando de provisionamento da CA. Nunca grava
	 * nada: quem chama é responsável por guardar a chave fora do banco.
	 */
	public static KeyPair newCaKeyPair() {
		try {
			return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (GeneralSecurityException falha) {
			throw new IllegalStateException("Ed25519 indisponivel neste JDK", falha);
		}
	}
}
package com.socialcoisa.rede.network.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.S3CredentialRepository;
import com.socialcoisa.rede.network.domain.S3Credential;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Access Keys do gateway S3 pelo painel (V357 + esta rota).
 *
 * <p><b>Antes:</b> a tabela e o repositório existiam, mas criar uma credencial só era possível por
 * SQL direto no banco — o que exige acesso de administrador ao banco, não ao painel, e deixa o segredo
 * passando por histórico de terminal e log de sessão. <b>Agora:</b> o dono cria a própria credencial
 * em {@code POST /network/storage/access-keys}, recebe o segredo <b>uma única vez</b> na resposta, e
 * revoga em {@code DELETE /network/storage/access-keys/{id}}.</p>
 *
 * <h2>As decisões que valem a pena estar ditas</h2>
 *
 * <ul>
 * <li><b>O segredo não volta nunca.</b> Ele é gravado cifrado (AES-GCM do {@code SecretCipher}) e o
 * hash fica para conferência; a listagem mostra access key, escopo e estado, nunca o segredo. É o que
 * permite o usuário saber o que tem sem que a credencial vaze por uma tela.</li>
 * <li><b>O escopo é validado, não copiado.</b> Um verbo fora de GET/PUT/DELETE/LIST/HEAD é recusado
 * ({@code InvalidArgument}): aceitar um verbo que o gateway não entende criaria uma credencial que
 * "tem permissão" para algo inexistente — e uma que o SDK tentaria usar e falharia depois.</li>
 * <li><b>Dono é quem está logado.</b> {@code ownerUserId} não vem da requisição; vem da sessão. Um
 * corpo que pedisse "criar para outra conta" criaria credencial de terceiro.</li>
 * <li><b>Revogar é fim, não pausa.</b> {@code revokedAt} não se limpa: a mesma Access Key revogada e
 * recriada seria a mesma credencial com o mesmo histórico de uso. Quem precisa de nova, cria nova.</li>
 * <li><b>Teto de credenciais ativas.</b> Sem teto, uma conta pode gerar milhares de credenciais e cada
 * requisição do gateway procurar por uma delas. O teto é por dono e conta só as ativas.</li>
 * </ul>
 */
@Service
public class StorageAccessKeyService {

	/**
	 * Quantas credenciais o dono pode manter <b>ativas</b>. O histórico (revogadas) não conta e não é
	 * podado: é a trilha de quem usou o quê. Acima de 20 ativas o dono está acumulando chave sem
	 * revogar a antiga — e cada requisição do gateway procura por uma delas.
	 */
	public static final int MAX_ACTIVE_PER_OWNER = 20;

	/** Teto da listagem: o dobro das ativas, porque revogadas entram e não ocupam o espaço das ativas. */
	private static final int LIST_LIMIT = MAX_ACTIVE_PER_OWNER * 2;

	private static final List<String> VERBOS = List.of("GET", "PUT", "DELETE", "LIST", "HEAD");

	private static final SecureRandom RANDOM = new SecureRandom();

	private final S3CredentialRepository credentials;
	private final AuditTrail audit;
	private final Clock clock;

	StorageAccessKeyService(S3CredentialRepository credentials, AuditTrail audit, Clock clock) {
		this.credentials = credentials;
		this.audit = audit;
		this.clock = clock;
	}

	/** O que o painel vê: sem o segredo. */
	public record View(String id, String accessKey, String allowedBucket, String allowedOperations,
			boolean revoked, String createdAt) {
	}

	/** A criação: o segredo aparece aqui e em lugar nenhum mais. */
	public record Created(View credential, String secretKey) {
	}

	@org.springframework.transaction.annotation.Transactional
	public Created create(String ownerUserId, String bucket, String operations) {
		// 32 caracteres é o tamanho que a coluna da V357 e a convenção da AWS usam (`AKIA` + 16
		// maiúsculas + 12). Um prefixo maior estouraria a coluna e o 409 do banco não diria por quê.
		String accessKey = "AKIA" + hexadecimal(8).toUpperCase(Locale.ROOT) + hexadecimal(6).toUpperCase(Locale.ROOT);
		String secret = "eN" + hexadecimal(28);
		String escopo = normalizar(operations);
		if (bucket != null && !bucket.isBlank() && !bucket.matches("[a-z0-9][a-z0-9.-]{1,62}")) {
			throw new ApiException(400, "InvalidArgument", "Nome de bucket invalido para escopo.");
		}
		// Todas as linhas do dono, e só então o filtro de ativas: pedir "20 ativas" ao banco não existe —
		// o limite é por linha, e revogadas entrariam na conta.
		List<S3Credential> ativas = credentials.findByOwner(ownerUserId, LIST_LIMIT).stream()
			.filter(S3Credential::usable)
			.toList();
		if (ativas.size() >= MAX_ACTIVE_PER_OWNER) {
			throw new ApiException(409, "STORAGE_ACCESS_KEY_LIMIT",
				"Limite de " + MAX_ACTIVE_PER_OWNER + " credenciais ativas por conta. Revogue uma antes de criar outra.");
		}
		S3Credential salva = credentials.save(new S3Credential("s3c_" + hexadecimal(12), accessKey, ownerUserId,
			bucket == null || bucket.isBlank() ? null : bucket, escopo, null, clock.instant()), secret);
		// A auditoria registra o id e o escopo, nunca o segredo: a trilha é lida por mais gente que o dono.
		audit.record(ownerUserId, "STORAGE_ACCESS_KEY_CREATED", salva.id(),
			"{\"accessKey\":\"" + salva.accessKey() + "\",\"bucket\":\"" + (salva.allowedBucket() == null ? ""
				: salva.allowedBucket()) + "\",\"operations\":\"" + escopo + "\"}");
		return new Created(view(salva), secret);
	}

	public List<View> list(String ownerUserId) {
		return credentials.findByOwner(ownerUserId, LIST_LIMIT).stream().map(StorageAccessKeyService::view)
			.toList();
	}

	/**
	 * Revoga, exigindo que a credencial seja do dono que pede. Credencial de outra conta é 404 — para
	 * quem pergunta ela não existe (dizer 403 confirmaria o id).
	 *
	 * <p>Transacional porque são escritas que só têm sentido juntas: a marca de revogação e a linha de
	 * auditoria. Uma revogação sem a trilha seria uma chave que some do acesso sem registro de quem a
	 * tirou.</p>
	 */
	@org.springframework.transaction.annotation.Transactional
	public View revoke(String ownerUserId, String credentialId) {
		S3Credential alvo = credentials.findForOwner(ownerUserId, credentialId)
			.orElseThrow(() -> new ApiException(404, "STORAGE_ACCESS_KEY_NOT_FOUND",
				"Credencial inexistente ou de outra conta."));
		Instant agora = clock.instant();
		// A instrução leva o dono junto: uma credencial de outra conta não é revogada nem por corrida.
		// `false` para uma já revogada é o mesmo estado final pedido — idempotente, não erro.
		boolean revogada = credentials.revoke(alvo.id(), ownerUserId, agora);
		if (revogada) {
			audit.record(ownerUserId, "STORAGE_ACCESS_KEY_REVOKED", alvo.id(),
				"{\"accessKey\":\"" + alvo.accessKey() + "\"}");
		}
		return view(new S3Credential(alvo.id(), alvo.accessKey(), alvo.ownerUserId(), alvo.allowedBucket(),
			alvo.allowedOperations(), alvo.revokedAt() == null ? agora : alvo.revokedAt(), alvo.createdAt()));
	}

	/**
	 * O escopo normalizado: verbos em maiúsculas, sem repetição, na ordem em que o gateway os entende.
	 *
	 * <p>Vazio é recusado. Uma credencial sem verbo é uma credencial que não faz nada — quem a cria
	 * acha que criou acesso.</p>
	 */
	static String normalizar(String operations) {
		if (operations == null || operations.isBlank()) {
			return "GET";
		}
		List<String> pedidos = java.util.Arrays.stream(operations.split("[\\s,]+"))
			.filter(v -> !v.isBlank())
			.map(v -> v.toUpperCase(Locale.ROOT))
			.distinct()
			.toList();
		for (String verbo : pedidos) {
			if (!VERBOS.contains(verbo)) {
				throw new ApiException(400, "InvalidArgument",
					"Verbo S3 desconhecido: " + verbo + ". Aceitos: " + String.join(" ", VERBOS) + ".");
			}
		}
		if (pedidos.isEmpty()) {
			return "GET";
		}
		return String.join(" ", pedidos);
	}

	private static View view(S3Credential credential) {
		return new View(credential.id(), credential.accessKey(), credential.allowedBucket(),
			credential.allowedOperations(), credential.revokedAt() != null,
			credential.createdAt() == null ? null : credential.createdAt().toString());
	}

	private static String hexadecimal(int bytes) {
		byte[] dados = new byte[bytes];
		RANDOM.nextBytes(dados);
		return HexFormat.of().formatHex(dados);
	}
}
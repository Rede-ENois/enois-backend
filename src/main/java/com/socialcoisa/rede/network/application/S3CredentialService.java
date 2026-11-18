package com.socialcoisa.rede.network.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.S3CredentialRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.S3Credential;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * As Access Keys do gateway S3 administradas pelo painel (brief §11).
 *
 * <p>Antes, a credencial só nascia por SQL direto na tabela do V357: o dono não tinha como pedir uma
 * chave, e ninguém tinha como revogar sem acesso ao banco. Aqui existem as duas operações, e nada
 * além — criar e revogar. Rotação é criar outra e revogar a antiga, que é o que permite sobrepor as
 * duas sem janela cega.</p>
 *
 * <p><b>O segredo aparece uma vez.</b> Ele é devolvido só na resposta da criação; depois disso o
 * banco guarda o hash (para nada — o SigV4 recalcula o HMAC) e o cifrado (para o gateway conferir).
 * Nenhuma consulta devolve o claro, e não há operação para lê-lo de volta.</p>
 *
 * <p>Escopo: a credencial nasce sempre <b>do dono que a pediu</b> e nunca aponta para conta alheia —
 * o dono não é parâmetro da rota, é a sessão. É a mesma regra que a donagem do bucket passou a
 * exigir no gateway (doc 18 §4.0).</p>
 */
@Service
public class S3CredentialService {

	/** O prefixo que identifica a chave como desta instalação em log e em ferramenta de cliente. */
	static final String ACCESS_KEY_PREFIX = "AKIAENOIS";

	/**
	 * Os verbos que uma credencial pode receber. É o mesmo conjunto que o gateway sabe autenticar:
	 * aceitar outro aqui criaria uma credencial que autentica e não serve para nada.
	 */
	static final Set<String> OPERACOES = Set.of("GET", "PUT", "LIST", "DELETE");

	/** Bucket do Storage (V357, {@code allowed_bucket varchar(63)}): minúsculas, sem barra. */
	private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]");

	private static final int ACCESS_KEY_BYTES = 8;
	private static final int SECRET_BYTES = 24;
	private static final int LIMITE_DE_LISTA = 200;

	private final S3CredentialRepository credentials;
	private final StorageCatalogRepository catalog;
	private final AuditTrail audit;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public S3CredentialService(S3CredentialRepository credentials, StorageCatalogRepository catalog, AuditTrail audit,
			Clock clock) {
		this.credentials = credentials;
		this.catalog = catalog;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Cria uma credencial para o dono da sessão e devolve o segredo em claro, uma vez só.
	 *
	 * @param allowedBucket       {@code null} = todos os buckets do dono (nunca "todos da rede")
	 * @param allowedOperations   verbos separados por espaço; vazio vira {@code "GET"} (o padrão do V357)
	 */
	public Criada criar(String ownerUserId, String allowedBucket, String allowedOperations) {
		String bucket = allowedBucket == null || allowedBucket.isBlank() ? null : allowedBucket.trim();
		if (bucket != null) {
			if (!BUCKET.matcher(bucket).matches()) {
				throw new ApiException(400, "STORAGE_S3_BUCKET_INVALID",
					"O bucket da credencial nao e um nome valido do Storage.");
			}
			// Só credencial para bucket próprio: a chave nasceria inutilizável no gateway, que confere
			// a donagem em todo verbo (doc 18 §4.0).
			if (!catalog.bucketsOf(ownerUserId).contains(bucket)) {
				throw new ApiException(403, "STORAGE_S3_BUCKET_FOREIGN",
					"Este bucket nao e do dono da sessao.");
			}
		}
		String verbos = normalizarOperacoes(allowedOperations);
		Instant now = clock.instant();
		String id = NodeIds.next("s3c");
		String accessKey = ACCESS_KEY_PREFIX + aleatorio(ACCESS_KEY_BYTES);
		String secret = aleatorio(SECRET_BYTES);
		credentials.save(new S3Credential(id, accessKey, ownerUserId, bucket, verbos, null, now), secret);
		audit.record(ownerUserId, "STORAGE_S3_CREDENTIAL_CREATED", id,
			"{\"bucket\":" + (bucket == null ? "null" : "\"" + bucket + "\"") + ",\"operations\":\"" + verbos + "\"}");
		return new Criada(new S3Credential(id, accessKey, ownerUserId, bucket, verbos, null, now), secret);
	}

	/** As credenciais do dono, mais recentes primeiro. O segredo não sai daqui. */
	public List<S3Credential> listar(String ownerUserId) {
		return credentials.findByOwner(ownerUserId, LIMITE_DE_LISTA);
	}

	/**
	 * Revoga uma credencial do dono. A de outro dono responde 404, não 403: quem não tem a chave não
	 * precisa saber que ela existe.
	 */
	public void revogar(String ownerUserId, String credentialId) {
		S3Credential alvo = credentials.findByOwner(ownerUserId, LIMITE_DE_LISTA).stream()
			.filter(c -> c.id().equals(credentialId))
			.findFirst()
			.orElseThrow(() -> new ApiException(404, "STORAGE_S3_CREDENTIAL_UNKNOWN",
				"Credencial nao encontrada."));
		if (credentials.revoke(alvo.id(), clock.instant())) {
			audit.record(ownerUserId, "STORAGE_S3_CREDENTIAL_REVOKED", alvo.id(), "{}");
		}
		// Revogar duas vezes é no-op: a credencial já está fora de uso, e repetir não é erro.
	}

	private static String normalizarOperacoes(String allowedOperations) {
		if (allowedOperations == null) {
			return "GET";
		}
		if (allowedOperations.isBlank()) {
			// `null` é "não escolhi" (o padrão do V357); em branco é escolha de ninguém — e aceitar
			// assim daria uma credencial que autentica e não pode nada.
			throw new ApiException(400, "STORAGE_S3_OPERATION_REQUIRED",
				"Informe ao menos uma operacao para a credencial.");
		}
		List<String> verbos = Arrays.stream(allowedOperations.trim().toUpperCase(Locale.ROOT).replace(',', ' ')
			.split("\\s+"))
			.filter(v -> !v.isBlank())
			.distinct()
			.toList();
		Set<String> desconhecidos = verbos.stream().filter(v -> !OPERACOES.contains(v))
			.collect(Collectors.toSet());
		if (!desconhecidos.isEmpty()) {
			throw new ApiException(400, "STORAGE_S3_OPERATION_INVALID",
				"Operacao desconhecida para a credencial: " + String.join(", ", desconhecidos) + ".");
		}
		if (verbos.isEmpty()) {
			throw new ApiException(400, "STORAGE_S3_OPERATION_REQUIRED",
				"Informe ao menos uma operacao para a credencial.");
		}
		// Ordem fixa: a mesma entrada dá a mesma linha no banco, e o diff do painel fica legível.
		return List.of("GET", "PUT", "LIST", "DELETE").stream().filter(verbos::contains).collect(Collectors.joining(" "));
	}

	/**
	 * Entropia em hexadecimal maiúsculo: cabe em {@code access_key varchar(32)} (20 + 16) e em
	 * {@code secret_encrypted varchar(255)}, e não traz caractere que precise de escape em cabeçalho.
	 */
	private String aleatorio(int bytes) {
		byte[] buffer = new byte[bytes];
		random.nextBytes(buffer);
		return HexFormat.of().withUpperCase().formatHex(buffer);
	}

	/** A credencial criada e o segredo em claro — a única vez que ele existe fora do cifrado. */
	public record Criada(S3Credential credential, String secret) {

		}
}
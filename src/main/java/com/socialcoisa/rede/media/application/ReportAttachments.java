package com.socialcoisa.rede.media.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.security.Hashes;

import jakarta.annotation.PostConstruct;

/**
 * Endereço com o qual a <b>cópia de uma denúncia</b> sai do servidor.
 *
 * <p>Ela é o arquivo que a própria pessoa enviou e que ainda não passou por revisão de imagem:
 * pode conter dado pessoal e conteúdo ilegal. Duas providências:</p>
 *
 * <ol>
 * <li><b>No bucket</b>: a cópia nasce em chave própria ({@value #KEY_PREFIX}{@code <id do
 *   asset>/<resto>}), fora de {@code rede.media.s3.prefix} — o prefixo público. A política do bucket
 *   pode negar leitura pública por prefixo, sem precisar de política por objeto.</li>
 * <li><b>No cliente</b>: o endereço entregue é o do envio ({@code /media/attachments/<id>}), com
 *   prazo curto e assinatura. Sem assinatura, sem arquivo — e nenhum endereço do bucket aparece na
 *   resposta.</li>
 * </ol>
 *
 * <p>Três leituras, cada uma concedida a quem tem direito:</p>
 * <ul>
 * <li><b>dono</b> — quem enviou pode baixar a própria cópia;</li>
 * <li><b>moderador</b> — a prova da análise;</li>
 * <li><b>assinatura</b> — o endereço curto que o painel de denúncia usa.</li>
 * </ul>
 */
@Component
public class ReportAttachments {

	private static final Logger LOG = LoggerFactory.getLogger(ReportAttachments.class);

	/** Prefixo das provas no bucket, de propósito fora do prefixo público. */
	static final String KEY_PREFIX = "reports/capture/";

	/** Pasta de upload de sempre (a mesma do {@code StorageUrlFactory}). */
	static final String UPLOADS_PREFIX = "uploads/";

	/** Menor segredo aceito: a assinatura é a única barreira entre um id conhecido e o arquivo. */
	private static final int MIN_SECRET_LENGTH = 32;

	static final String SCOPE_OWNER = "owner";
	static final String SCOPE_MODERATOR = "moderator";

	private final String backendUrl;
	private final String secret;
	private final long ttlSeconds;
	private final MediaPublicHost publicHost;
	private final Clock clock;

	public ReportAttachments(@Value("${rede.backend.url:}") String backendUrl,
			@Value("${rede.media.attachments.secret:}") String secret,
			@Value("${rede.media.attachments.ttl-seconds:300}") long ttlSeconds, MediaPublicHost publicHost,
			Clock clock) {
		String base = backendUrl == null ? "" : backendUrl.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		this.backendUrl = base;
		this.secret = secret == null ? "" : secret.trim();
		this.ttlSeconds = Math.max(1, ttlSeconds);
		this.publicHost = publicHost;
		this.clock = clock;
	}

	/**
	 * Sem segredo não se emite endereço: a leitura responderia 503 de qualquer forma, e o aviso no
	 * boot faz o operador perceber antes da primeira denúncia.
	 */
	@PostConstruct
	void avisarSemSegredo() {
		if (!hasSecret()) {
			LOG.warn("rede.media.attachments.secret nao configurado ou curto demais: a copia anexada a "
				+ "denuncia nao pode ser aberta. Configure REDE_MEDIA_ATTACHMENTS_SECRET.");
		}
	}

	public boolean hasSecret() {
		return secret.length() >= MIN_SECRET_LENGTH;
	}

	/**
	 * Aviso de boot: o bucket deste ambiente precisa negar leitura pública em {@value #KEY_PREFIX}.
	 * Com CDN na frente do bucket, a negação tem de estar na CDN — senão o prefixo privado vira
	 * endereço público como qualquer outro.
	 */
	@PostConstruct
	void avisarPrefixoSemCdn() {
		String host = publicHost.mediaHost() == null ? "" : publicHost.mediaHost();
		if (host.endsWith(".amazonaws.com")) {
			LOG.info("copia de denuncia em '{}' (bucket S3): negue leitura publica nesse prefixo na policy "
				+ "do bucket.", KEY_PREFIX);
			return;
		}
		LOG.warn("copia de denuncia sai por '{}', que nao e o bucket S3: confirme que a CDN NAO serve "
			+ "o prefixo '{}' publicamente.", host, KEY_PREFIX);
	}

	/**
	 * Chave do objeto da cópia: o prefixo privado mais a chave que o objeto teria no caminho público
	 * ({@code media_<id>/…}), dentro da pasta de upload de sempre.
	 *
	 * <p>{@code publicObjectKey} pode vir já como o caminho do bucket ({@code uploads/…}) ou como a
	 * chave da cópia; a transformação vale para as duas formas, então chamar de novo com o resultado
	 * não muda nada — é o que permite o endereço do asset ser, ao mesmo tempo, onde o objeto está e
	 * o que a subida pelo proxy confere.</p>
	 */
	public String keyFor(String mediaId, String publicObjectKey) {
		String declared = publicObjectKey == null ? "" : publicObjectKey;
		while (declared.startsWith("/")) {
			declared = declared.substring(1);
		}
		if (declared.startsWith(KEY_PREFIX)) {
			declared = declared.substring(KEY_PREFIX.length());
		}
		if (declared.startsWith(UPLOADS_PREFIX)) {
			declared = declared.substring(UPLOADS_PREFIX.length());
		}
		return KEY_PREFIX + UPLOADS_PREFIX + mediaId + "/" + tail(declared, mediaId);
	}

	/** O resto do caminho depois da pasta do asset, se ela vier na frente. */
	private static String tail(String declared, String mediaId) {
		String folder = mediaId + "/";
		return declared.startsWith(folder) ? declared.substring(folder.length()) : declared;
	}

	/** O endereço do dono (o link anexado à denúncia): {@code null} sem segredo configurado. */
	public String ownerUrl(String mediaId) {
		return hasSecret() ? url(mediaId, SCOPE_OWNER) : null;
	}

	/** O endereço que o painel de moderação usa. */
	public String moderatorUrl(String mediaId) {
		return hasSecret() ? url(mediaId, SCOPE_MODERATOR) : null;
	}

	/**
	 * O id confirmado pela assinatura, com o escopo do endereço. {@link Optional#empty()} para
	 * assinatura ausente, errada, vencida ou segredo não configurado — a recusa é sempre a mesma.
	 */
	public Optional<Signed> verify(String mediaId, String scope, String expires, String presented) {
		if (!hasSecret() || !StringUtils.hasText(mediaId) || !StringUtils.hasText(scope)
				|| !(SCOPE_OWNER.equals(scope) || SCOPE_MODERATOR.equals(scope))) {
			return Optional.empty();
		}
		Long until = expiry(expires, presented);
		if (until == null) {
			return Optional.empty();
		}
		byte[] expected = signature(mediaId, scope, until).getBytes(StandardCharsets.US_ASCII);
		byte[] actual = presented.trim().getBytes(StandardCharsets.US_ASCII);
		return Arrays.equals(expected, actual) ? Optional.of(new Signed(mediaId.trim(), scope)) : Optional.empty();
	}

	/** Endereço assinado: o id e o escopo que a assinatura cobre. */
	public record Signed(String mediaId, String scope) {
	}

	/** {@code null} quando o prazo está ausente ou malformado. */
	Long expiry(String expires, String signature) {
		if (!StringUtils.hasText(expires) || !StringUtils.hasText(signature)) {
			return null;
		}
		long until;
		try {
			until = Long.parseLong(expires.trim());
		}
		catch (NumberFormatException malformed) {
			return null;
		}
		return until < clock.instant().getEpochSecond() ? null : until;
	}

	private String url(String mediaId, String scope) {
		long expires = clock.instant().getEpochSecond() + ttlSeconds;
		return backendUrl + "/media/attachments/" + mediaId + "?scope=" + scope + "&expires=" + expires
			+ "&signature=" + signature(mediaId, scope, expires);
	}

	private String signature(String mediaId, String scope, long expires) {
		return Hashes.sha256Hex(secret + "\n" + mediaId + "\n" + scope + "\n" + expires);
	}}

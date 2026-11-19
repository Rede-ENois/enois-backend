package com.socialcoisa.rede.media.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.media.application.port.out.MediaPublicUrlVerifier;
import com.socialcoisa.rede.media.application.port.out.MediaS3Copier;
import com.socialcoisa.rede.media.application.port.out.MediaSourceDownloader;
import com.socialcoisa.rede.media.application.port.out.MediaUrlMigrationRepository;
import com.socialcoisa.rede.media.application.port.out.MediaUrlReferenceUpdater;

/**
 * Migração OPORTUNISTA das URLs legadas (ImageKit / caminho relativo antigo) para o S3.
 *
 * <p>O acesso real do app a uma imagem de post passa pelo endpoint de mídia (a grade do post pede
 * `GET /media/{id}`) e às fotos de perfil pelas respostas de perfil — é nesses pontos que a
 * migração é agendada, fora do caminho crítico. O objeto é copiado UMA vez (claim atômico no
 * banco, seguro entre réplicas), verificado na URL pública e só então as referências persistidas
 * são trocadas por UPDATE condicional.</p>
 *
 * <p>Ligada pelo MESMO interruptor da migração em lote (`rede.media.migration.enabled`): com ela
 * desligada, este serviço nem existe — nada migra, nem acessando. A troca das referências só
 * depende da verificação pública (o flip da URL), então CDN fora = referências intactas.</p>
 */
@Service
@ConditionalOnProperty(name = "rede.media.migration.enabled", havingValue = "true")
public class LazyUrlMigrationService {

	private static final Logger log = LoggerFactory.getLogger(LazyUrlMigrationService.class);

	private static final Duration STALE_CLAIM = Duration.ofMinutes(10);
	private static final int MAX_ATTEMPTS = 5;
	private static final int SWEEP_LIMIT = 50;

	private final MediaUrlMigrationRepository migrations;
	private final MediaSourceDownloader downloader;
	private final MediaS3Copier copier;
	private final MediaPublicUrlVerifier verifier;
	private final MediaUrlReferenceUpdater references;
	private final Executor executor;
	private final Clock clock;
	private final String imageKitEndpoint;

	public LazyUrlMigrationService(MediaUrlMigrationRepository migrations, MediaSourceDownloader downloader,
			MediaS3Copier copier, MediaPublicUrlVerifier verifier, MediaUrlReferenceUpdater references,
			Executor mediaLazyMigrationExecutor, Clock clock,
			@Value("${rede.media.imagekit.url-endpoint:}") String imageKitEndpoint) {
		this.migrations = migrations;
		this.downloader = downloader;
		this.copier = copier;
		this.verifier = verifier;
		this.references = references;
		this.executor = mediaLazyMigrationExecutor;
		this.clock = clock;
		this.imageKitEndpoint = imageKitEndpoint == null ? "" : imageKitEndpoint.trim();
	}

	/**
	 * Chamado no caminho de LEITURA (perfil, mídia): agenda a migração sem bloquear a resposta.
	 * Ignora qualquer URL que não seja legítima do ImageKit (SSRF) ou de caminho relativo antigo.
	 */
	public void onAccess(String sourceUrl) {
		if (!StringUtils.hasText(sourceUrl) || !isLegacy(sourceUrl)) {
			return;
		}
		String url = sourceUrl.trim();
		String hash = hash(url);
		try {
			migrations.ensurePending(hash, url, clock.instant());
			java.time.Instant now = clock.instant();
			if (!migrations.claim(hash, now, now.minus(STALE_CLAIM), MAX_ATTEMPTS)) {
				return; // outra réplica já está copiando, já copiou ou esgotou tentativas
			}
			executor.execute(() -> copy(hash, url));
		}
		catch (RejectedExecutionException filaCheia) {
			// Fila do processo cheia: o objeto fica PENDING e a varredura em lote cobre depois.
			log.debug("Migração oportunista adiada (fila cheia): {}", hash);
		}
		catch (RuntimeException falha) {
			log.warn("Falha ao agendar a migração da URL {}: {}", hash, falha.getMessage());
		}
	}

	/** Cópia + verificação + troca de referências (roda fora do request, no executor). */
	void copy(String hash, String sourceUrl) {
		Path source = null;
		try {
			source = downloader.download(absoluteSourceOf(sourceUrl));
			String key = s3Key(sourceUrl);
			String s3Url = copier.copy(key, source, mimeOf(sourceUrl));
			migrations.markCopied(hash, s3Url, clock.instant());
			log.info("URL migrada para o S3 (aguardando verificação pública): {}", hash);
			tryActivate(hash, sourceUrl, s3Url);
		}
		catch (RuntimeException falha) {
			migrations.markFailed(hash, falha.getMessage(), clock.instant());
			log.warn("Falha ao migrar a URL {} para o S3: {}", hash, falha.getMessage());
		}
		finally {
			if (source != null) {
				try {
					Files.deleteIfExists(source);
				}
				catch (Exception ignored) {
				}
			}
		}
	}

	/** Flip: só ativa (e troca referências) quando a URL pública do S3 responde. */
	private void tryActivate(String hash, String sourceUrl, String s3Url) {
		if (!verifier.verify(s3Url, mimeOf(sourceUrl))) {
			log.warn("S3 ainda nao responde para {}; referencias mantidas no provedor antigo", hash);
			return;
		}
		migrations.markActive(hash, clock.instant());
		int trocadas = references.updateReferences(sourceUrl, s3Url);
		log.info("URL ativada no S3 ({} referencias atualizadas): {}", trocadas, hash);
	}

	/**
	 * Fase de flip + varredura em lote: ativa o que já copiou e agenda as URLs legadas que ainda
	 * não foram acessadas por ninguém (cobre todas as entidades do registro).
	 */
	@Scheduled(fixedDelayString = "${rede.media.migration.delay-ms:30000}",
		initialDelayString = "${rede.media.migration.initial-delay-ms:45000}")
	void flipAndSweep() {
		try {
			for (MediaUrlMigrationRepository.Row row : migrations.findForFlip(SWEEP_LIMIT)) {
				if (row.s3Url() != null) {
					tryActivate(row.urlHash(), row.sourceUrl(), row.s3Url());
				}
			}
			for (String url : references.findLegacyUrls(SWEEP_LIMIT)) {
				onAccess(url);
			}
		}
		catch (RuntimeException falha) {
			log.warn("Falha na rodada da migração oportunista: {}", falha.getMessage());
		}
	}

	/** Legítima = https no host do ImageKit configurado (allowlist) OU caminho relativo antigo. */
	boolean isLegacy(String url) {
		if (url.startsWith("/uploads/")) {
			return true;
		}
		URI parsed;
		try {
			parsed = URI.create(url);
		}
		catch (IllegalArgumentException malformada) {
			return false;
		}
		if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null) {
			return false;
		}
		String host = parsed.getHost().toLowerCase(Locale.ROOT);
		return host.equals("ik.imagekit.io") || (!imageKitEndpoint.isEmpty() && host.equals(hostOf(imageKitEndpoint)));
	}

	private String hostOf(String endpoint) {
		try {
			String host = URI.create(endpoint).getHost();
			return host == null ? "" : host.toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException malformado) {
			return "";
		}
	}

	/**
	 * URL ABSOLUTA de download: caminho relativo antigo ("/uploads/...") ganha a base do ImageKit
	 * e o `%2F` vira barra (o CDN aceita o caminho decodificado) — a referência GRAVADA continua
	 * sendo a relativa, é ela que o UPDATE condicional procura.
	 */
	String absoluteSourceOf(String sourceUrl) {
		String reversed = sourceUrl.replace("%2F", "/").replace("%2f", "/");
		if (!reversed.startsWith("/uploads/")) {
			return reversed;
		}
		String base = imageKitEndpoint.isEmpty() ? "https://ik.imagekit.io/socialcoisa"
			: (imageKitEndpoint.endsWith("/") ? imageKitEndpoint.substring(0, imageKitEndpoint.length() - 1)
				: imageKitEndpoint);
		return base + reversed;
	}

	/**
	 * Chave no S3 = caminho da URL sem a barra inicial (mesma forma da cópia em lote). O caminho
	 * do ENDPOINT do ImageKit (a conta, ex.: `/socialcoisa`) sai junto: a chave começa em
	 * `uploads/...`, igual à do lote, senão o mesmo objeto ganharia duas chaves.
	 */
	String s3Key(String sourceUrl) {
		String path;
		if (sourceUrl.startsWith("/uploads/")) {
			path = sourceUrl;
		}
		else {
			path = URI.create(sourceUrl).getPath();
			String basePath = endpointPath();
			if (!basePath.isEmpty() && path != null && path.startsWith(basePath)) {
				path = path.substring(basePath.length());
			}
		}
		String decoded = java.net.URLDecoder.decode(path == null ? "" : path, StandardCharsets.UTF_8);
		return decoded.startsWith("/") ? decoded.substring(1) : decoded;
	}

	/** Caminho da conta no endpoint (fallback: a conta histórica `socialcoisa`). */
	private String endpointPath() {
		String basePath = null;
		if (!imageKitEndpoint.isEmpty()) {
			basePath = URI.create(imageKitEndpoint).getPath();
		}
		if (basePath == null || basePath.isEmpty() || "/".equals(basePath)) {
			return imageKitEndpoint.isEmpty() ? "/socialcoisa" : "";
		}
		return basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
	}

	/** Tipo pelo sufixo do arquivo; o S3 usa isso como Content-Type na leitura. */
	String mimeOf(String sourceUrl) {
		String path = sourceUrl.toLowerCase(Locale.ROOT).split("\\?")[0];
		int dot = path.lastIndexOf('.');
		String ext = dot < 0 ? "" : path.substring(dot + 1);
		return switch (ext) {
			case "png" -> "image/png";
			case "jpg", "jpeg" -> "image/jpeg";
			case "gif" -> "image/gif";
			case "webp" -> "image/webp";
			case "avif" -> "image/avif";
			case "svg" -> "image/svg+xml";
			case "mp4" -> "video/mp4";
			case "webm" -> "video/webm";
			case "mov" -> "video/quicktime";
			case "mp3" -> "audio/mpeg";
			case "ogg" -> "audio/ogg";
			case "wav" -> "audio/wav";
			default -> "application/octet-stream";
		};
	}

	static String hash(String url) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (Exception impossivel) {
			throw new IllegalStateException("SHA-256 indisponivel", impossivel);
		}
	}
}

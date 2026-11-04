package com.socialcoisa.rede.media.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.in.CreateMediaUploadUseCase;
import com.socialcoisa.rede.media.application.port.in.GetMediaAssetUseCase;
import com.socialcoisa.rede.media.application.port.in.RequestPostMediaDeletionUseCase;
import com.socialcoisa.rede.media.application.port.in.RequestMediaDeletionUseCase;
import com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaDeletionJobRepository;
import com.socialcoisa.rede.media.application.port.out.MediaUploadSigner;
import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.StatusResponse;

@Service
public class MediaService implements CreateMediaUploadUseCase, GetMediaAssetUseCase, RequestMediaDeletionUseCase,
		RequestPostMediaDeletionUseCase, ValidateMediaOwnershipUseCase,
		com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase {

	private static final Logger LOG = LoggerFactory.getLogger(MediaService.class);


	/**
	 * Teto de uma midia: 500MB por arquivo. O S3 aceita bem mais (o PUT assinado vai direto do
	 * browser para o bucket, sem passar pelo pod), entao o limite aqui e do produto, nao do
	 * transporte. Quem recusar e o proprio servico, com mensagem clara.
	 */
	public static final long MAX_MEDIA_BYTES = 500L * 1024L * 1024L;
	/** Janela do upload direto ao S3 e da limpeza do asset pendente (arquivos de ate 500MB). */
	private static final Duration UPLOAD_WINDOW = Duration.ofMinutes(60);

	/**
	 * Types that may be served by the CDN, each with the extension of the server-generated object
	 * name. HTML/SVG/active JS stay out on purpose.
	 */
	private static final java.util.Map<String, String> ALLOWED_MIME_TYPES = java.util.Map.ofEntries(
		java.util.Map.entry("image/jpeg", ".jpg"), java.util.Map.entry("image/png", ".png"),
		java.util.Map.entry("image/gif", ".gif"), java.util.Map.entry("image/webp", ".webp"),
		java.util.Map.entry("image/avif", ".avif"),
		java.util.Map.entry("video/mp4", ".mp4"), java.util.Map.entry("video/webm", ".webm"),
		java.util.Map.entry("video/quicktime", ".mov"),
		java.util.Map.entry("audio/mpeg", ".mp3"), java.util.Map.entry("audio/ogg", ".ogg"),
		java.util.Map.entry("audio/wav", ".wav"), java.util.Map.entry("audio/webm", ".weba"),
		java.util.Map.entry("application/pdf", ".pdf"));
	private static final java.util.Set<String> IMAGE_MIME_TYPES = java.util.Set.of(
		"image/jpeg", "image/png", "image/gif", "image/webp", "image/avif");
	/** Upload kinds narrower than the general list; any other purpose (posts, messages) uses the full list. */
	private static final java.util.Map<String, java.util.Set<String>> MIME_TYPES_BY_PURPOSE = java.util.Map.of(
		"AVATAR", IMAGE_MIME_TYPES, "BANNER", IMAGE_MIME_TYPES, "COVER", IMAGE_MIME_TYPES,
		"BADGE", IMAGE_MIME_TYPES, "GAME_ICON", IMAGE_MIME_TYPES, "GAME_BANNER", IMAGE_MIME_TYPES,
		"MENTOR_BANNER", IMAGE_MIME_TYPES, "RESUME", java.util.Set.of("application/pdf"));

	private final MediaAssetRepository mediaAssetRepository;
	private final MediaDeletionJobRepository mediaDeletionJobRepository;
	private final StorageUrlFactory storageUrlFactory;
	/** So existe com storage S3 (ver S3StorageConfig); sem ele, o fluxo do ImageKit segue igual. */
	private final java.util.Optional<MediaUploadSigner> uploadSigner;
	/** Só existe com a migração ligada; presente aqui para a leitura agendar a cópia oportunista. */
	private final org.springframework.beans.factory.ObjectProvider<LazyMediaCopyTrigger> lazyCopy;
	private final Clock clock;

	public MediaService(MediaAssetRepository mediaAssetRepository, MediaDeletionJobRepository mediaDeletionJobRepository,
			StorageUrlFactory storageUrlFactory, java.util.Optional<MediaUploadSigner> uploadSigner,
			org.springframework.beans.factory.ObjectProvider<LazyMediaCopyTrigger> lazyCopy, Clock clock) {
		this.mediaAssetRepository = mediaAssetRepository;
		this.mediaDeletionJobRepository = mediaDeletionJobRepository;
		this.storageUrlFactory = storageUrlFactory;
		this.uploadSigner = uploadSigner;
		this.lazyCopy = lazyCopy;
		this.clock = clock;
	}

	@Override
	@Transactional
	public MediaUploadView createUpload(CreateMediaUploadCommand command) {
		String mimeType = validate(command);
		// URGENTE (19/10/2026): o ImageKit foi comprometido e NAO recebe mais arquivo novo.
		// Upload so existe com o S3 configurado (REDE_MEDIA_STORAGE=s3 + bucket); sem isso a
		// resposta e 503 — de proposito, nunca um caminho de escrita no provedor antigo.
		MediaUploadSigner signer = uploadSigner.orElseThrow(() -> new ApiException(503, "UPLOADS_DISABLED",
			"O envio de midia esta temporariamente desativado enquanto o armazenamento e migrado."));
		String mediaId = newId("media");
		// The object name is generated here: the client filename is kept as metadata only and never
		// becomes part of the storage key or the public URL.
		String objectKey = storageUrlFactory.objectKey(mediaId, "file" + ALLOWED_MIME_TYPES.get(mimeType));
		MediaAsset asset = MediaAsset.pending(mediaId, command.ownerId(), command.fileName().trim(),
			mimeType, command.size(), normalize(command.purpose()), objectKey,
			storageUrlFactory.cdnUrl(objectKey), clock.instant(), clock.instant().plus(UPLOAD_WINDOW),
			storageUrlFactory.provider());
		MediaAsset saved = mediaAssetRepository.save(asset);
		try {
			MediaUploadView view = MediaUploadView.fromSigned(saved,
				signer.sign(objectKey, saved.mimeType(), saved.size()));
			// Metadados do upload (nunca o conteúdo): permite no CloudWatch ver quem subiu o quê,
			// tamanho/tipo e o provedor, e cruzar com o mediaId que aparece nos posts.
			LOG.info("media_upload_signed mediaId={} ownerId={} mime={} bytes={} purpose={} provider={}",
				saved.id(), command.ownerId(), saved.mimeType(), command.size(), saved.purpose(), saved.storageProvider());
			return view;
		}
		catch (ApiException failure) {
			throw failure;
		}
		catch (RuntimeException assinaturaFalhou) {
			// Credencial do S3 ausente/ruim (IRSA) virava 500 generico — o operador nao tinha o
			// que ler. Aqui a falha fica explicita e o cliente recebe algo acionavel.
			LOG.error("media_upload_signing_failed ownerId={} mime={} bytes={} error={}", command.ownerId(),
				command.mimeType(), command.size(), assinaturaFalhou.getClass().getSimpleName());
			throw new ApiException(503, "STORAGE_UNAVAILABLE",
				"O armazenamento de midia esta indisponivel no momento. Tente novamente em instantes.");
		}
	}

	@Override
	@Transactional(readOnly = true)
	public MediaAssetView getMedia(String mediaId) {
		MediaAsset asset = findMedia(mediaId);
		// Leitura = oportunidade de migração: quem pede o asset (grade do post, preview) agenda a
		// cópia para o S3 sem esperar. Resposta idêntica à de sempre; nada bloqueia.
		lazyCopy.ifAvailable(trigger -> trigger.copySoon(asset));
		return MediaAssetView.from(asset);
	}

	@Override
	@Transactional(readOnly = true)
	public java.util.Map<String, MediaSummaryView> visibleByIds(java.util.Collection<String> mediaIds) {
		if (mediaIds == null || mediaIds.isEmpty()) {
			return java.util.Map.of();
		}
		List<String> ids = mediaIds.stream().filter(StringUtils::hasText).distinct().toList();
		return mediaAssetRepository.findByIds(ids).stream()
			.filter(MediaAsset::isVisible)
			.collect(java.util.stream.Collectors.toMap(MediaAsset::id, MediaSummaryView::from));
	}

	@Override
	@Transactional
	public StatusResponse requestDeletion(RequestMediaDeletionCommand command) {
		MediaAsset asset = findMedia(command.mediaId());
		if (!asset.belongsTo(command.ownerId())) {
			throw new ApiException(403, "MEDIA_OWNER_MISMATCH", "Midia nao pertence ao usuario autenticado.");
		}
		requestDeletion(asset, null);
		return new StatusResponse(asset.id(), "DELETE_REQUESTED");
	}

	@Override
	@Transactional
	public List<String> requestPostMediaDeletion(UserId ownerId, PostId postId, List<String> mediaIds) {
		if (mediaIds == null || mediaIds.isEmpty()) {
			return List.of();
		}
		List<MediaAsset> assets = mediaAssetRepository.findByIds(mediaIds).stream()
			.filter(asset -> asset.belongsTo(ownerId))
			.toList();
		assets.forEach(asset -> requestDeletion(asset, postId));
		return assets.stream().map(MediaAsset::id).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public void validateOwnedBy(UserId ownerId, List<String> mediaIds) {
		if (mediaIds == null || mediaIds.isEmpty()) {
			return;
		}
		List<String> normalized = mediaIds.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
		List<MediaAsset> assets = mediaAssetRepository.findByIds(normalized);
		if (assets.size() != normalized.size()) {
			throw new ApiException(404, "MEDIA_NOT_FOUND", "Uma ou mais midias nao foram encontradas.");
		}
		boolean hasForeignMedia = assets.stream().anyMatch(asset -> !asset.belongsTo(ownerId));
		if (hasForeignMedia) {
			throw new ApiException(403, "MEDIA_OWNER_MISMATCH", "Todas as midias precisam pertencer ao autor do post.");
		}
	}

	public void requestDeletion(MediaAsset asset, PostId postId) {
		boolean changed = asset.requestDeletion(clock.instant());
		if (changed) {
			mediaAssetRepository.save(asset);
		}
		mediaDeletionJobRepository.request(newId("media_deletion_job"), asset.id(), postId, asset.ownerId(),
			clock.instant());
	}

	private MediaAsset findMedia(String mediaId) {
		if (!StringUtils.hasText(mediaId)) {
			throw new ApiException(400, "INVALID_MEDIA_ID", "Identificador de midia obrigatorio.");
		}
		return mediaAssetRepository.findById(mediaId.trim())
			.orElseThrow(() -> new ApiException(404, "MEDIA_NOT_FOUND", "Midia nao encontrada."));
	}

	/** Returns the normalized MIME type (lower case, without parameters) that gets signed and stored. */
	private String validate(CreateMediaUploadCommand command) {
		if (command.ownerId() == null) {
			throw new ApiException(401, "UNAUTHENTICATED", "Usuario autenticado ausente.");
		}
		if (!StringUtils.hasText(command.fileName())) {
			throw new ApiException(400, "INVALID_FILE_NAME", "Nome do arquivo obrigatorio.");
		}
		if (!StringUtils.hasText(command.mimeType())) {
			throw new ApiException(400, "INVALID_MIME_TYPE", "Tipo de midia obrigatorio.");
		}
		String mime = command.mimeType().trim().toLowerCase(java.util.Locale.ROOT);
		int semicolon = mime.indexOf(';');
		if (semicolon >= 0) {
			mime = mime.substring(0, semicolon).trim();
		}
		if (!ALLOWED_MIME_TYPES.containsKey(mime)) {
			throw new ApiException(400, "MEDIA_TYPE_NOT_ALLOWED",
				"Tipo de arquivo nao permitido. Envie imagem, video, audio ou PDF.");
		}
		String purpose = command.purpose() == null ? "" : command.purpose().trim().toUpperCase(java.util.Locale.ROOT);
		java.util.Set<String> allowedForPurpose = MIME_TYPES_BY_PURPOSE.get(purpose);
		if (allowedForPurpose != null && !allowedForPurpose.contains(mime)) {
			throw new ApiException(400, "MEDIA_TYPE_NOT_ALLOWED",
				"Tipo de arquivo nao permitido para este envio.");
		}
		if (command.size() <= 0) {
			throw new ApiException(400, "INVALID_MEDIA_SIZE", "Tamanho da midia deve ser maior que zero.");
		}
		if (command.size() > MAX_MEDIA_BYTES) {
			throw new ApiException(400, "MEDIA_TOO_LARGE",
				"Mídia deve ter no máximo " + (MAX_MEDIA_BYTES / (1024L * 1024L)) + "MB.");
		}
		return mime;
	}

	/** Whether a stored type may be rendered inline by the browser (same list the upload accepts). */
	public static boolean isAllowedMimeType(String mimeType) {
		if (mimeType == null) {
			return false;
		}
		int semicolon = mimeType.indexOf(';');
		String base = semicolon >= 0 ? mimeType.substring(0, semicolon) : mimeType;
		return ALLOWED_MIME_TYPES.containsKey(base.trim().toLowerCase(java.util.Locale.ROOT));
	}

	private String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}

package com.socialcoisa.rede.media.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

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
import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.StatusResponse;

@Service
public class MediaService implements CreateMediaUploadUseCase, GetMediaAssetUseCase, RequestMediaDeletionUseCase,
		RequestPostMediaDeletionUseCase, ValidateMediaOwnershipUseCase {

	private final MediaAssetRepository mediaAssetRepository;
	private final MediaDeletionJobRepository mediaDeletionJobRepository;
	private final StorageUrlFactory storageUrlFactory;
	private final Clock clock;

	public MediaService(MediaAssetRepository mediaAssetRepository, MediaDeletionJobRepository mediaDeletionJobRepository,
			StorageUrlFactory storageUrlFactory, Clock clock) {
		this.mediaAssetRepository = mediaAssetRepository;
		this.mediaDeletionJobRepository = mediaDeletionJobRepository;
		this.storageUrlFactory = storageUrlFactory;
		this.clock = clock;
	}

	@Override
	@Transactional
	public MediaUploadView createUpload(CreateMediaUploadCommand command) {
		validate(command);
		String mediaId = newId("media");
		String objectKey = storageUrlFactory.objectKey(mediaId, command.fileName().trim());
		MediaAsset asset = MediaAsset.pending(mediaId, command.ownerId(), command.fileName().trim(),
			command.mimeType().trim(), command.size(), normalize(command.purpose()), objectKey,
			storageUrlFactory.cdnUrl(objectKey), clock.instant(), clock.instant().plus(Duration.ofMinutes(15)));
		MediaAsset saved = mediaAssetRepository.save(asset);
		return MediaUploadView.from(saved, storageUrlFactory.uploadUrl(objectKey));
	}

	@Override
	@Transactional(readOnly = true)
	public MediaAssetView getMedia(String mediaId) {
		return MediaAssetView.from(findMedia(mediaId));
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

	private void validate(CreateMediaUploadCommand command) {
		if (command.ownerId() == null) {
			throw new ApiException(401, "UNAUTHENTICATED", "Usuario autenticado ausente.");
		}
		if (!StringUtils.hasText(command.fileName())) {
			throw new ApiException(400, "INVALID_FILE_NAME", "Nome do arquivo obrigatorio.");
		}
		if (!StringUtils.hasText(command.mimeType())) {
			throw new ApiException(400, "INVALID_MIME_TYPE", "Tipo de midia obrigatorio.");
		}
		if (command.size() <= 0) {
			throw new ApiException(400, "INVALID_MEDIA_SIZE", "Tamanho da midia deve ser maior que zero.");
		}
		if (command.size() > 50L * 1024L * 1024L) {
			throw new ApiException(400, "MEDIA_TOO_LARGE", "Midia deve ter no maximo 50MB.");
		}
	}

	private String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}

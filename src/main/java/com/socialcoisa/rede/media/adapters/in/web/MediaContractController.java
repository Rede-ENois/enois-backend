package com.socialcoisa.rede.media.adapters.in.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/media")
class MediaContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	MediaContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@PostMapping("/uploads")
	ResponseEntity<String> upload(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody UploadRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/media/uploads", idempotencyKey, hash, () -> {
			String mediaId = newId("media");
			MediaUploadView view = new MediaUploadView(mediaId, request.fileName(), request.mimeType(), request.size(),
				"PENDING", "NONE", "/api/media/" + mediaId, "/dev/uploads/" + mediaId, Instant.now());
			return new CommandResponse(201, view);
		});
	}

	@GetMapping("/{mediaId}")
	MediaAssetView media(@PathVariable String mediaId) {
		return new MediaAssetView(mediaId, null, null, 0, "PENDING", "NONE", "/api/media/" + mediaId, null);
	}

	@DeleteMapping("/{mediaId}")
	ResponseEntity<String> delete(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mediaId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("mediaId", mediaId));
		return idempotency.execute(user.id(), "DELETE", "/api/media/{mediaId}", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(mediaId, "DELETE_REQUESTED")));
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	record UploadRequest(String fileName, String mimeType, long size, String purpose) {
	}

	record MediaAssetView(String id, String fileName, String mimeType, long size, String status, String deleteStatus,
			String cdnUrl, String objectKey) {
	}

	record MediaUploadView(String id, String fileName, String mimeType, long size, String status, String deleteStatus,
			String cdnUrl, String uploadUrl, Instant expiresAt) {
	}
}

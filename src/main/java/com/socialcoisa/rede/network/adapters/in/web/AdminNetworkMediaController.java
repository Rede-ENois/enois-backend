package com.socialcoisa.rede.network.adapters.in.web;

import java.time.Instant;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.network.application.MediaTranscodeService;
import com.socialcoisa.rede.network.domain.MediaJobStatus;
import com.socialcoisa.rede.network.domain.MediaTranscodeJob;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * Transcodes delegados no painel. Admin, como o resto de {@code /admin/network}.
 *
 * <p>A linha mostra a origem fixada e o resultado medido: quem abriu a tela tem de poder dizer se o
 * arquivo que o nó devolveu é o transcode daquele arquivo, sem precisar baixar nada.</p>
 */
@RestController
@RequestMapping("/admin/network/media")
class AdminNetworkMediaController {

	private final MediaTranscodeService media;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	AdminNetworkMediaController(MediaTranscodeService media, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard) {
		this.media = media;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record JobView(String mediaId, String mimeType, long sourceBytes, String sourceSha256, String resultKey,
			Long resultBytes, String resultSha256, String status, String taskId, String lastError, Instant createdAt,
			Instant verifiedAt) {

		static JobView of(MediaTranscodeJob job) {
			return new JobView(job.mediaId(), job.mimeType(), job.sourceBytes(), job.sourceSha256(), job.resultKey(),
				job.resultBytes(), job.resultSha256(), job.status().name(), job.taskId(), job.lastError(),
				job.createdAt(), job.verifiedAt());
		}
	}

	record MediaOverview(long pending, long verified, long rejected) {
	}

	@GetMapping
	List<JobView> list(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "200") int limit) {
		admin(sessionToken);
		return media.list(limit).stream().map(JobView::of).toList();
	}

	@GetMapping("/overview")
	MediaOverview overview(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		admin(sessionToken);
		return new MediaOverview(media.countByStatus(MediaJobStatus.PENDING),
			media.countByStatus(MediaJobStatus.VERIFIED), media.countByStatus(MediaJobStatus.REJECTED));
	}

	/**
	 * Enfileira o transcode de uma mídia. Não apaga nem sobrescreve nada: cria a tarefa e o job; o
	 * resultado entra em staging e só é usado quando conferir.
	 */
	@PostMapping("/{mediaId}/transcode")
	JobView request(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String mediaId) {
		CurrentUser admin = admin(sessionToken);
		MediaTranscodeJob job = media.request(mediaId);
		return JobView.of(job);
	}

	private CurrentUser admin(String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		adminGuard.requireAdmin(user);
		return user;
	}
}
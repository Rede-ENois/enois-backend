package com.socialcoisa.rede.feed.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.RestorePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.profile.application.ContributorTagView;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Desfazer a exclusao de um post. So o autor, e so enquanto o post estiver excluido (logicamente).
 */
@Service
public class RestorePostService implements RestorePostUseCase {

	private final PostRepository postRepository;
	private final FeedDistributionService feedDistributionService;
	private final AuditLogRepository auditLogRepository;
	private final UserProfileRepository userProfileRepository;
	private final com.socialcoisa.rede.contributions.application.port.out.UserSealRepository userSeals;
	private final com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase mediaSummaries;

	public RestorePostService(PostRepository postRepository, FeedDistributionService feedDistributionService,
			AuditLogRepository auditLogRepository, UserProfileRepository userProfileRepository,
			com.socialcoisa.rede.contributions.application.port.out.UserSealRepository userSeals,
			com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase mediaSummaries) {
		this.mediaSummaries = mediaSummaries;
		this.postRepository = postRepository;
		this.feedDistributionService = feedDistributionService;
		this.auditLogRepository = auditLogRepository;
		this.userProfileRepository = userProfileRepository;
		this.userSeals = userSeals;
	}

	@Override
	@Transactional
	public PostView restore(UserId userId, PostId postId) {
		Post post = postRepository.findById(postId)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		if (!post.belongsTo(userId)) {
			throw new ApiException(403, "POST_OWNER_MISMATCH", "Post nao pertence ao usuario autenticado.");
		}
		if (!post.undelete()) {
			throw new ApiException(409, "POST_NOT_DELETED", "Este post nao esta excluido.");
		}
		Post saved = postRepository.save(post);
		feedDistributionService.distribute(saved);
		auditLogRepository.record(userId, "POST_RESTORED", saved.id().value(), null);
		var author = userProfileRepository.findById(saved.authorId().value()).orElse(null);
		PostView view = author != null
			? PostView.from(saved, author.username(), author.name(), author.avatar(), author.allBadges())
				.withAvatarFrame(author.avatarFrame())
				.withAuthorStyle(saved.authorIdentity() == AuthorIdentity.SOCIAL ? NameStyleView.from(author) : null)
				.withContributor(saved.authorIdentity() == AuthorIdentity.SOCIAL && userSeals.hasActiveSeal(author.id())
					? ContributorTagView.from(author) : null)
			: PostView.from(saved);
		return view.withMedia(mediaSummaries.visibleByIds(saved.mediaIds()));
	}
}

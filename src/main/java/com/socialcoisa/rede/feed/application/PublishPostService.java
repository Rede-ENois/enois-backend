package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.in.PublishPostUseCase;
import com.socialcoisa.rede.feed.application.port.out.IdGenerator;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.media.application.port.in.ValidateMediaOwnershipUseCase;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class PublishPostService implements PublishPostUseCase {

	private final PostRepository postRepository;
	private final IdGenerator idGenerator;
	private final Clock clock;
	private final FeedDistributionService feedDistributionService;
	private final OutboxEventRepository outboxEventRepository;
	private final ValidateMediaOwnershipUseCase validateMediaOwnershipUseCase;
	private final NotificationService notificationService;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository;

	public PublishPostService(PostRepository postRepository, IdGenerator idGenerator, Clock clock,
			FeedDistributionService feedDistributionService, OutboxEventRepository outboxEventRepository,
			ValidateMediaOwnershipUseCase validateMediaOwnershipUseCase,
			NotificationService notificationService,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository) {
		this.postRepository = postRepository;
		this.idGenerator = idGenerator;
		this.clock = clock;
		this.feedDistributionService = feedDistributionService;
		this.outboxEventRepository = outboxEventRepository;
		this.validateMediaOwnershipUseCase = validateMediaOwnershipUseCase;
		this.notificationService = notificationService;
		this.userProfileRepository = userProfileRepository;
	}

	@Override
	@Transactional
	public PostView publish(PublishPostCommand command) {
		validate(command);
		List<String> tags = command.tags() == null ? List.of() : command.tags().stream()
			.filter(StringUtils::hasText)
			.map(String::trim)
			.distinct()
			.toList();
		List<String> mediaIds = command.mediaIds() == null ? List.of() : command.mediaIds().stream()
			.filter(StringUtils::hasText)
			.map(String::trim)
			.distinct()
			.toList();
		validateMediaOwnershipUseCase.validateOwnedBy(command.authorId(), mediaIds);
		String communityId = StringUtils.hasText(command.communityId()) ? command.communityId().trim() : null;
		Post post = Post.publish(idGenerator.newPostId(), command.authorId(), command.type(), command.content().trim(),
			tags, mediaIds, communityId, clock.instant());
		Post saved = postRepository.save(post);
		feedDistributionService.distribute(saved);
		outboxEventRepository.append("PostPublished", saved.id().value(), "{\"postId\":\"" + saved.id().value() + "\"}");
		notifyMentions(command.mentionedUserIds(), command.authorId().value(), saved.id().value(), "post");
		UserProfile author = userProfileRepository.findById(command.authorId().value()).orElse(null);
		if (author != null) {
			return PostView.from(saved, author.username(), author.name(), author.avatar());
		}
		return PostView.from(saved);
	}

	private void notifyMentions(List<String> mentionedUserIds, String actorId, String postId, String kind) {
		if (mentionedUserIds == null) return;
		for (String mentionedId : mentionedUserIds.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList()) {
			if (mentionedId.equals(actorId)) continue;
			if (userProfileRepository.findById(mentionedId).isEmpty()) continue;
			String title = "Menção em " + ("post".equals(kind) ? "publicação" : "comentário");
			String href = "/api/feed/posts/" + postId;
			notificationService.create(mentionedId, "MENTION", actorId, title,
				"Você foi mencionado em um " + ("post".equals(kind) ? "publicação" : "comentário") + ".",
				href, "Ver");
		}
	}

	private void validate(PublishPostCommand command) {
		if (command.type() == null) {
			throw new ApiException(400, "INVALID_POST_TYPE", "Tipo de post obrigatorio.");
		}
		if (!StringUtils.hasText(command.content())) {
			throw new ApiException(400, "INVALID_CONTENT", "Conteudo obrigatorio.");
		}
		if (command.content().trim().length() > 1024) {
			throw new ApiException(400, "CONTENT_TOO_LONG", "Conteudo deve ter no maximo 1024 caracteres.");
		}
		if (command.tags() != null && command.tags().stream().filter(StringUtils::hasText).distinct().count() > 5) {
			throw new ApiException(400, "TOO_MANY_TAGS", "Post deve ter no maximo 5 tags.");
		}
		if (command.mediaIds() != null && command.mediaIds().stream().filter(StringUtils::hasText).distinct().count() > 5) {
			throw new ApiException(400, "TOO_MANY_MEDIA", "Post deve ter no maximo 5 midias.");
		}
	}
}

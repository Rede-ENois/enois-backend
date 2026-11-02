package com.socialcoisa.rede.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.outbox.OutboxEventHandler;

@Component
class NotificationOutboxEventHandler implements OutboxEventHandler {

	private static final Logger LOG = LoggerFactory.getLogger(NotificationOutboxEventHandler.class);

	private final NotificationService notificationService;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final UserProfileRepository userProfileRepository;

	NotificationOutboxEventHandler(NotificationService notificationService, PostRepository postRepository,
			CommentRepository commentRepository, UserProfileRepository userProfileRepository) {
		this.notificationService = notificationService;
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.userProfileRepository = userProfileRepository;
	}

	@Override
	public void handle(String eventType, String aggregateId, String payload) {
		try {
			switch (eventType) {
				case "PostCommented" -> handlePostCommented(payload);
				case "PostReacted" -> handlePostReacted(payload);
				case "CommentReacted" -> handleCommentReacted(payload);
				default -> {
				}
			}
		} catch (Exception e) {
			LOG.warn("Erro ao processar evento {}: {}", eventType, e.getMessage());
		}
	}

	private void handlePostCommented(String payload) {
		String postId = extractValue(payload, "postId");
		String authorId = extractValue(payload, "authorId");
		if (postId == null || authorId == null) return;
		Post post = postRepository.findById(new PostId(postId)).orElse(null);
		if (post == null) return;
		if (post.authorId().value().equals(authorId)) return;
		String actorName = userProfileRepository.findById(authorId)
			.map(p -> p.name()).orElse("Alguém");
		notificationService.create(post.authorId().value(), "NEW_COMMENT", authorId,
			"Novo comentário", actorName + " comentou no seu post.",
			"/api/feed/posts/" + postId, "Ver");
	}

	private void handlePostReacted(String payload) {
		String postId = extractValue(payload, "postId");
		String authorId = extractValue(payload, "authorId");
		if (postId == null || authorId == null) return;
		Post post = postRepository.findById(new PostId(postId)).orElse(null);
		if (post == null) return;
		if (post.authorId().value().equals(authorId)) return;
		String actorName = userProfileRepository.findById(authorId)
			.map(p -> p.name()).orElse("Alguém");
		notificationService.createGrouped(post.authorId().value(), "NEW_REACTION", "NEW_REACTION:post:" + postId,
			authorId, "Nova reação", actorName + " reagiu ao seu post.",
			count -> othersReacted(actorName, count, "ao seu post"), "/api/feed/posts/" + postId,
			// Several people: the post opens with the list of who reacted, one by one.
			"/api/feed/posts/" + postId + "?reacoes=1", "Ver");
	}

	private void handleCommentReacted(String payload) {
		String commentId = extractValue(payload, "commentId");
		String postId = extractValue(payload, "postId");
		String authorId = extractValue(payload, "authorId");
		if (commentId == null || authorId == null) return;
		Comment comment = commentRepository.findById(commentId).orElse(null);
		if (comment == null) return;
		if (comment.authorId().value().equals(authorId)) return;
		String actorName = userProfileRepository.findById(authorId)
			.map(p -> p.name()).orElse("Alguém");
		notificationService.createGrouped(comment.authorId().value(), "COMMENT_REACTION",
			"COMMENT_REACTION:comment:" + commentId, authorId, "Reação no comentário",
			actorName + " reagiu ao seu comentário.", count -> othersReacted(actorName, count, "ao seu comentário"),
			postId != null ? "/api/feed/posts/" + postId : null, postId != null ? "/api/feed/posts/" + postId : null,
			"Ver");
	}

	/** "Ana e mais 1 pessoa reagiram ao seu post." / "Ana e mais 14 pessoas reagiram ao seu post." */
	static String othersReacted(String actorName, int count, String target) {
		int others = count - 1;
		return actorName + " e mais " + others + (others == 1 ? " pessoa" : " pessoas") + " reagiram " + target + ".";
	}

	private String extractValue(String json, String key) {
		String search = "\"" + key + "\":\"";
		int start = json.indexOf(search);
		if (start < 0) return null;
		start += search.length();
		int end = json.indexOf("\"", start);
		if (end < 0) return null;
		return json.substring(start, end);
	}
}

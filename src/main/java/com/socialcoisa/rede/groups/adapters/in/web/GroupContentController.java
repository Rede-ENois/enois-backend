package com.socialcoisa.rede.groups.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.groups.application.GroupFeedService;
import com.socialcoisa.rede.groups.domain.GroupComment;
import com.socialcoisa.rede.groups.domain.GroupPost;
import com.socialcoisa.rede.groups.domain.GroupTopic;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

/**
 * Tópicos e feed privado do grupo.
 *
 * Tudo aqui exige sessão: não há leitura pública de tópico nem de publicação — quem não é membro
 * recebe 403 mesmo sabendo os ids (a autorização é no serviço, não na tela).
 */
@RestController
@RequestMapping("/groups")
class GroupContentController {

	private final GroupFeedService feed;
	private final CurrentUserResolver currentUserResolver;
	private final UserProfileRepository profiles;
	private final IdempotencyApplicationService idempotency;
	private final CursorCodec cursorCodec;

	GroupContentController(GroupFeedService feed, CurrentUserResolver currentUserResolver,
			UserProfileRepository profiles, IdempotencyApplicationService idempotency, CursorCodec cursorCodec) {
		this.feed = feed;
		this.currentUserResolver = currentUserResolver;
		this.profiles = profiles;
		this.idempotency = idempotency;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping("/{groupId}/topics")
	PageResponse<TopicView> topics(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String groupId) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		var items = feed.listTopics(groupId, user.id().value()).stream().map(GroupContentController::topicView).toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{groupId}/topics")
	ResponseEntity<String> createTopic(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @RequestBody TopicRequest request) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/topics", idempotencyKey, hash, () -> {
			GroupTopic topic = feed.createTopic(groupId, user.id().value(), request.name(), request.description());
			return new CommandResponse(201, topicView(topic));
		});
	}

	@PatchMapping("/{groupId}/topics/{topicId}")
	ResponseEntity<String> updateTopic(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String topicId, @RequestBody TopicRequest request) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "topicId", topicId, "request", request));
		return idempotency.execute(user.id(), "PATCH", "/api/groups/{groupId}/topics/{topicId}", idempotencyKey, hash,
			() -> {
				GroupTopic topic = feed.updateTopic(groupId, topicId, request.name(), request.description(),
					user.id().value());
				return new CommandResponse(200, topicView(topic));
			});
	}

	@PostMapping("/{groupId}/topics/{topicId}/archive")
	ResponseEntity<String> archiveTopic(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String topicId) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "topicId", topicId, "action", "archive"));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/topics/{topicId}/archive",
			idempotencyKey, hash, () -> {
				GroupTopic topic = feed.archiveTopic(groupId, topicId, user.id().value());
				return new CommandResponse(200, topicView(topic));
			});
	}

	@PostMapping("/{groupId}/topics/reorder")
	ResponseEntity<String> reorderTopics(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @RequestBody ReorderTopicsRequest request) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/topics/reorder", idempotencyKey, hash,
			() -> {
				var topics = feed.reorderTopics(groupId, request.topicIds(), user.id().value())
					.stream().map(GroupContentController::topicView).toList();
				return new CommandResponse(200, new PageResponse<>(topics, null));
			});
	}

	@PostMapping("/{groupId}/posts")
	ResponseEntity<String> publish(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @RequestBody GroupPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/posts", idempotencyKey, hash, () -> {
			GroupPost post = feed.publish(groupId, request.topicId(), user.id().value(), request.content(),
				request.mediaIds());
			return new CommandResponse(201, postView(post, null));
		});
	}

	@GetMapping("/{groupId}/posts")
	PageResponse<GroupPostView> posts(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String groupId, @RequestParam(required = false) String topicId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<GroupPost> posts = feed.listPosts(groupId, topicId, user.id().value(), before, limit);
		// A reação de quem está lendo vem numa consulta só (sem N+1), como no feed principal.
		Map<String, ReactionType> mine = feed.myReactions(groupId, user.id().value(),
			posts.stream().map(GroupPost::id).toList());
		var items = posts.stream().map(post -> postView(post, mine.get(post.id()))).toList();
		String nextCursor = posts.size() == limit
			? cursorCodec.encode(posts.get(posts.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	/** A única publicação fixada no topo do grupo; 404 quando não há. */
	@GetMapping("/{groupId}/pinned")
	GroupPostView pinned(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String groupId) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		GroupPost post = feed.pinnedPost(groupId, user.id().value())
			.orElseThrow(() -> new ApiException(404, "NO_PINNED_POST", "Nao ha publicacao fixada neste grupo."));
		return postView(post, feed.myReactions(groupId, user.id().value(), List.of(post.id())).get(post.id()));
	}

	@PostMapping("/{groupId}/posts/{postId}/pin")
	ResponseEntity<String> pin(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "postId", postId, "action", "pin"));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/posts/{postId}/pin", idempotencyKey, hash,
			() -> {
				GroupPost post = feed.pinPost(groupId, postId, user.id().value());
				return new CommandResponse(200, postView(post, null));
			});
	}

	@DeleteMapping("/{groupId}/posts/{postId}/pin")
	ResponseEntity<String> unpin(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "postId", postId, "action", "unpin"));
		return idempotency.execute(user.id(), "DELETE", "/api/groups/{groupId}/posts/{postId}/pin", idempotencyKey,
			hash, () -> {
				feed.unpinPost(groupId, postId, user.id().value());
				return new CommandResponse(200, new StatusResponse(postId, "UNPINNED"));
			});
	}

	@PostMapping("/{groupId}/posts/{postId}/comments")
	ResponseEntity<String> comment(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String postId, @RequestBody CommentRequest request) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "postId", postId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/posts/{postId}/comments",
			idempotencyKey, hash, () -> {
				GroupComment comment = feed.comment(groupId, postId, user.id().value(), request.content(),
					request.parentCommentId());
				return new CommandResponse(201, commentView(comment));
			});
	}

	@GetMapping("/{groupId}/posts/{postId}/comments")
	PageResponse<CommentView> comments(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@PathVariable String groupId, @PathVariable String postId,
			@RequestParam(defaultValue = "100") int limit) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		var items = feed.listComments(groupId, postId, user.id().value(), limit)
			.stream()
			.map(this::commentView)
			.toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping("/{groupId}/posts/{postId}/reactions")
	ResponseEntity<String> react(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String postId, @RequestBody ReactionRequest request) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "postId", postId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/groups/{groupId}/posts/{postId}/reactions",
			idempotencyKey, hash, () -> {
				boolean active = feed.toggleReaction(groupId, postId, user.id().value(), request.type());
				return new CommandResponse(200, new StatusResponse(postId, active ? "REACTED" : "UNREACTED"));
			});
	}

	@DeleteMapping("/{groupId}/posts/{postId}/reactions")
	ResponseEntity<String> unreact(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "postId", postId, "action", "unreact"));
		return idempotency.execute(user.id(), "DELETE", "/api/groups/{groupId}/posts/{postId}/reactions",
			idempotencyKey, hash, () -> {
				feed.removeReaction(groupId, postId, user.id().value());
				return new CommandResponse(200, new StatusResponse(postId, "UNREACTED"));
			});
	}

	@DeleteMapping("/{groupId}/posts/{postId}")
	ResponseEntity<String> removePost(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "postId", postId, "action", "remove"));
		return idempotency.execute(user.id(), "DELETE", "/api/groups/{groupId}/posts/{postId}", idempotencyKey, hash,
			() -> {
				feed.removePost(groupId, postId, user.id().value());
				return new CommandResponse(200, new StatusResponse(postId, "REMOVED"));
			});
	}

	@DeleteMapping("/{groupId}/posts/{postId}/comments/{commentId}")
	ResponseEntity<String> removeComment(@RequestHeader(value = "X-Session-Token", required = false) String token,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String groupId, @PathVariable String postId, @PathVariable String commentId) {
		CurrentUser user = currentUserResolver.resolve(null, token);
		String hash = idempotency.hash(Map.of("groupId", groupId, "postId", postId, "commentId", commentId,
			"action", "remove"));
		return idempotency.execute(user.id(), "DELETE", "/api/groups/{groupId}/posts/{postId}/comments/{commentId}",
			idempotencyKey, hash, () -> {
				feed.removeComment(groupId, postId, commentId, user.id().value());
				return new CommandResponse(200, new StatusResponse(commentId, "REMOVED"));
			});
	}

	private CommentView commentView(GroupComment comment) {
		UserProfile author = profiles.findById(comment.authorId()).orElse(null);
		return new CommentView(comment.id(), comment.postId(), comment.authorId(),
			author == null ? null : author.displayWithNickname(),
			author == null ? null : author.username(),
			author == null ? null : author.avatar(),
			author == null ? List.of() : author.allBadges(),
			comment.parentId(), comment.content(), comment.createdAt(), author == null ? null : author.avatarFrame());
	}

	private GroupPostView postView(GroupPost post, ReactionType myReaction) {
		UserProfile author = profiles.findById(post.authorId()).orElse(null);
		List<String> badges = author == null ? List.of() : author.allBadges();
		return new GroupPostView(post.id(), post.groupId(), post.topicId(), post.authorId(),
			author == null ? null : author.displayWithNickname(),
			author == null ? null : author.username(),
			author == null ? null : author.avatar(),
			badges,
			post.content(), post.mediaIds(), post.pinned(), post.reactions(), post.comments(),
			myReaction == null ? null : myReaction.name(), post.createdAt(), author == null ? null : author.avatarFrame());
	}

	private static TopicView topicView(GroupTopic topic) {
		return new TopicView(topic.id(), topic.groupId(), topic.name(), topic.description(), topic.position(),
			topic.status().name(), topic.createdAt());
	}

	record TopicRequest(String name, String description) {
	}

	record ReorderTopicsRequest(List<String> topicIds) {
	}

	record GroupPostRequest(String topicId, String content, List<String> mediaIds) {
	}

	record TopicView(String id, String groupId, String name, String description, int position, String status,
			Instant createdAt) {
	}

	record GroupPostView(String id, String groupId, String topicId, String authorId, String authorName,
			String authorUsername, String authorAvatar, List<String> authorBadges, String content,
			List<String> mediaIds, boolean pinned, int reactions, int comments, String myReaction,
			Instant createdAt, String authorAvatarFrame) {
	}

	record CommentRequest(String content, String parentCommentId) {
	}

	record ReactionRequest(String type) {
	}

	record CommentView(String id, String postId, String authorId, String authorName, String authorUsername,
			String authorAvatar, List<String> authorBadges, String parentCommentId, String content,
			Instant createdAt, String authorAvatarFrame) {
	}
}

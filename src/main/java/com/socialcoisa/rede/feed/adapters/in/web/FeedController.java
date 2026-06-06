package com.socialcoisa.rede.feed.adapters.in.web;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.BulkDeletePostsCommand;
import com.socialcoisa.rede.feed.application.CommentOnPostCommand;
import com.socialcoisa.rede.feed.application.DeletePostCommand;
import com.socialcoisa.rede.feed.application.GetFeedQuery;
import com.socialcoisa.rede.feed.application.GetCommentsQuery;
import com.socialcoisa.rede.feed.application.GetProfilePostsQuery;
import com.socialcoisa.rede.feed.application.PostActionCommand;
import com.socialcoisa.rede.feed.application.PublishPostCommand;
import com.socialcoisa.rede.feed.application.ReactToPostCommand;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.application.port.in.BulkDeletePostsUseCase;
import com.socialcoisa.rede.feed.application.port.in.CommentOnPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.DeletePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetCommentsUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetFeedUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetProfilePostsUseCase;
import com.socialcoisa.rede.feed.application.port.in.PublishPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.ReactToPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.SavePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.SharePostUseCase;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;

@RestController
@RequestMapping("/api")
class FeedController {

	private static final String USER_HEADER = "X-User-Id";

	private final PublishPostUseCase publishPostUseCase;
	private final GetFeedUseCase getFeedUseCase;
	private final GetProfilePostsUseCase getProfilePostsUseCase;
	private final DeletePostUseCase deletePostUseCase;
	private final BulkDeletePostsUseCase bulkDeletePostsUseCase;
	private final CommentOnPostUseCase commentOnPostUseCase;
	private final GetCommentsUseCase getCommentsUseCase;
	private final ReactToPostUseCase reactToPostUseCase;
	private final SavePostUseCase savePostUseCase;
	private final SharePostUseCase sharePostUseCase;
	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final CursorCodec cursorCodec;

	FeedController(PublishPostUseCase publishPostUseCase, GetFeedUseCase getFeedUseCase,
			GetProfilePostsUseCase getProfilePostsUseCase, DeletePostUseCase deletePostUseCase,
			BulkDeletePostsUseCase bulkDeletePostsUseCase, CommentOnPostUseCase commentOnPostUseCase,
			GetCommentsUseCase getCommentsUseCase, ReactToPostUseCase reactToPostUseCase,
			SavePostUseCase savePostUseCase, SharePostUseCase sharePostUseCase,
			IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver, CursorCodec cursorCodec) {
		this.publishPostUseCase = publishPostUseCase;
		this.getFeedUseCase = getFeedUseCase;
		this.getProfilePostsUseCase = getProfilePostsUseCase;
		this.deletePostUseCase = deletePostUseCase;
		this.bulkDeletePostsUseCase = bulkDeletePostsUseCase;
		this.commentOnPostUseCase = commentOnPostUseCase;
		this.getCommentsUseCase = getCommentsUseCase;
		this.reactToPostUseCase = reactToPostUseCase;
		this.savePostUseCase = savePostUseCase;
		this.sharePostUseCase = sharePostUseCase;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.cursorCodec = cursorCodec;
	}

	@PostMapping("/feed/posts")
	ResponseEntity<String> publish(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody PublishPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts", idempotencyKey, hash, () -> new CommandResponse(201,
			publishPostUseCase.publish(new PublishPostCommand(user.id(), request.type(), request.content(),
				request.tags(), request.mediaIds()))));
	}

	@GetMapping("/feed")
	Object feed(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(defaultValue = "FOR_YOU") FeedTab tab) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return getFeedUseCase.getFeed(new GetFeedQuery(user.id(), cursorCodec.decode(cursor), limit, tab));
	}

	@GetMapping("/users/{username}/posts")
	Object profilePosts(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken, @PathVariable String username,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit) {
		CurrentUser user = StringUtils.hasText(userId) || StringUtils.hasText(sessionToken)
			? currentUserResolver.resolve(userId, sessionToken) : currentUserResolver.resolve(username);
		return getProfilePostsUseCase.getProfilePosts(new GetProfilePostsQuery(user.id(), username,
			cursorCodec.decode(cursor), limit));
	}

	@DeleteMapping("/feed/posts/{postId}")
	ResponseEntity<String> delete(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestParam(required = false) String reason) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Map<String, String> payload = new LinkedHashMap<>();
		payload.put("postId", postId);
		payload.put("reason", reason == null ? "" : reason);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}", idempotencyKey, hash,
			() -> new CommandResponse(200, deletePostUseCase.delete(new DeletePostCommand(user.id(), new PostId(postId),
				reason))));
	}

	@PostMapping("/feed/posts/bulk-delete")
	ResponseEntity<String> bulkDelete(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody BulkDeletePostsRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		List<String> normalizedIds = request.postIds() == null ? List.of()
			: request.postIds().stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct()
				.sorted(Comparator.naturalOrder()).toList();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postIds", normalizedIds);
		payload.put("reason", request.reason() == null ? "" : request.reason());
		String hash = idempotency.hash(payload);
		List<PostId> postIds = normalizedIds.stream().map(PostId::new).toList();
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/bulk-delete", idempotencyKey, hash,
			() -> new CommandResponse(200, bulkDeletePostsUseCase.delete(new BulkDeletePostsCommand(user.id(), postIds,
				request.reason()))));
	}

	@PostMapping("/feed/posts/{postId}/comments")
	ResponseEntity<String> comment(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody CommentOnPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", postId);
		payload.put("parentCommentId", request.parentCommentId() == null ? "" : request.parentCommentId());
		payload.put("content", request.content() == null ? "" : request.content());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/comments", idempotencyKey, hash,
			() -> new CommandResponse(201, commentOnPostUseCase.comment(new CommentOnPostCommand(user.id(),
				new PostId(postId), request.parentCommentId(), request.content()))));
	}

	@GetMapping("/feed/posts/{postId}/comments")
	Object comments(@PathVariable String postId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit) {
		return getCommentsUseCase.getComments(new GetCommentsQuery(new PostId(postId), cursorCodec.decode(cursor), limit));
	}

	@PostMapping("/feed/posts/{postId}/reactions")
	ResponseEntity<String> react(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId, @RequestBody ReactToPostRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("postId", postId);
		payload.put("type", request.type() == null ? "" : request.type());
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/reactions", idempotencyKey, hash,
			() -> new CommandResponse(200, reactToPostUseCase.react(new ReactToPostCommand(user.id(),
				new PostId(postId), request.type()))));
	}

	@DeleteMapping("/feed/posts/{postId}/reactions")
	ResponseEntity<String> removeReaction(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Map<String, String> payload = Map.of("postId", postId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}/reactions", idempotencyKey, hash,
			() -> new CommandResponse(200, reactToPostUseCase.removeReaction(new ReactToPostCommand(user.id(),
				new PostId(postId), null))));
	}

	@PostMapping("/feed/posts/{postId}/saves")
	ResponseEntity<String> save(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Map<String, String> payload = Map.of("postId", postId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/saves", idempotencyKey, hash,
			() -> new CommandResponse(200, savePostUseCase.save(new PostActionCommand(user.id(), new PostId(postId)))));
	}

	@DeleteMapping("/feed/posts/{postId}/saves")
	ResponseEntity<String> removeSave(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Map<String, String> payload = Map.of("postId", postId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "DELETE", "/api/feed/posts/{postId}/saves", idempotencyKey, hash,
			() -> new CommandResponse(200, savePostUseCase.removeSave(new PostActionCommand(user.id(),
				new PostId(postId)))));
	}

	@PostMapping("/feed/posts/{postId}/shares")
	ResponseEntity<String> share(@RequestHeader(value = USER_HEADER, required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String postId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Map<String, String> payload = Map.of("postId", postId);
		String hash = idempotency.hash(payload);
		return idempotency.execute(user.id(), "POST", "/api/feed/posts/{postId}/shares", idempotencyKey, hash,
			() -> new CommandResponse(200, sharePostUseCase.share(new PostActionCommand(user.id(), new PostId(postId)))));
	}
}

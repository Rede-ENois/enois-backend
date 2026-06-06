package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.in.BulkDeletePostsUseCase;
import com.socialcoisa.rede.feed.application.port.in.DeletePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class DeletePostService implements DeletePostUseCase, BulkDeletePostsUseCase {

	private final PostRepository postRepository;
	private final FeedCacheRepository feedCacheRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final AuditLogRepository auditLogRepository;
	private final Clock clock;

	public DeletePostService(PostRepository postRepository, FeedCacheRepository feedCacheRepository,
			OutboxEventRepository outboxEventRepository, AuditLogRepository auditLogRepository, Clock clock) {
		this.postRepository = postRepository;
		this.feedCacheRepository = feedCacheRepository;
		this.outboxEventRepository = outboxEventRepository;
		this.auditLogRepository = auditLogRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public DeletePostResult delete(DeletePostCommand command) {
		Post post = postRepository.findById(command.postId())
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		if (!post.belongsTo(command.userId())) {
			throw new ApiException(403, "POST_OWNER_MISMATCH", "Post nao pertence ao usuario autenticado.");
		}
		if (!post.isActive()) {
			return new DeletePostResult(post.id().value(), "ALREADY_DELETED");
		}
		post.delete(clock.instant());
		postRepository.save(post);
		feedCacheRepository.removeEverywhere(post.id());
		outboxEventRepository.append("PostDeleted", post.id().value(), "{\"postId\":\"" + post.id().value() + "\"}");
		auditLogRepository.record(command.userId(), "POST_DELETED", post.id().value(), normalizeReason(command.reason()));
		return new DeletePostResult(post.id().value(), "DELETED");
	}

	@Override
	@Transactional
	public BulkDeleteResult delete(BulkDeletePostsCommand command) {
		List<PostId> postIds = normalizePostIds(command);
		Map<PostId, Post> foundPosts = new LinkedHashMap<>();
		for (Post post : postRepository.findByIds(postIds)) {
			foundPosts.put(post.id(), post);
		}
		for (Post post : foundPosts.values()) {
			if (!post.belongsTo(command.userId())) {
				throw new ApiException(403, "POST_OWNER_MISMATCH", "Todos os posts precisam pertencer ao usuario autenticado.");
			}
		}
		List<BulkDeleteItemResult> results = new ArrayList<>();
		for (PostId postId : postIds) {
			Post post = foundPosts.get(postId);
			if (post == null) {
				results.add(new BulkDeleteItemResult(postId.value(), "NOT_FOUND"));
				continue;
			}
			if (!post.isActive()) {
				results.add(new BulkDeleteItemResult(postId.value(), "ALREADY_DELETED"));
				continue;
			}
			post.delete(clock.instant());
			postRepository.save(post);
			feedCacheRepository.removeEverywhere(post.id());
			outboxEventRepository.append("PostDeleted", post.id().value(), "{\"postId\":\"" + post.id().value() + "\"}");
			auditLogRepository.record(command.userId(), "POST_BULK_DELETED", post.id().value(),
				normalizeReason(command.reason()));
			results.add(new BulkDeleteItemResult(post.id().value(), "MARKED_DELETED"));
		}
		return new BulkDeleteResult("COMPLETED", results);
	}

	private List<PostId> normalizePostIds(BulkDeletePostsCommand command) {
		if (command.postIds() == null || command.postIds().isEmpty()) {
			throw new ApiException(400, "EMPTY_POST_LIST", "Lista de posts obrigatoria.");
		}
		List<PostId> postIds = command.postIds().stream().distinct().toList();
		if (postIds.size() > 100) {
			throw new ApiException(400, "TOO_MANY_POSTS", "Bulk delete aceita no maximo 100 posts.");
		}
		return postIds;
	}

	private String normalizeReason(String reason) {
		return StringUtils.hasText(reason) ? reason.trim() : null;
	}
}

package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.in.CommentOnPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetCommentsUseCase;
import com.socialcoisa.rede.feed.application.port.in.ReactToPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.SavePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.SharePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.OutboxEventRepository;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.CommentStatus;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CursorCodec;

@Service
public class PostInteractionService implements CommentOnPostUseCase, GetCommentsUseCase, ReactToPostUseCase,
		SavePostUseCase, SharePostUseCase {

	private static final Instant DEFAULT_BEFORE = Instant.parse("9999-12-31T23:59:59Z");

	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final PostInteractionRepository postInteractionRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final CursorCodec cursorCodec;
	private final Clock clock;

	public PostInteractionService(PostRepository postRepository, CommentRepository commentRepository,
			PostInteractionRepository postInteractionRepository, OutboxEventRepository outboxEventRepository,
			CursorCodec cursorCodec, Clock clock) {
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.postInteractionRepository = postInteractionRepository;
		this.outboxEventRepository = outboxEventRepository;
		this.cursorCodec = cursorCodec;
		this.clock = clock;
	}

	@Override
	@Transactional
	public CommentView comment(CommentOnPostCommand command) {
		Post post = activePost(command.postId());
		String content = normalizeContent(command.content());
		String parentCommentId = normalizeParentComment(command.parentCommentId(), post.id());
		Comment comment = Comment.create(newId("comment"), post.id(), command.authorId(), parentCommentId, content,
			clock.instant());
		Comment saved = commentRepository.save(comment);
		post.addComment();
		postRepository.save(post);
		outboxEventRepository.append("PostCommented", post.id().value(), "{\"postId\":\"" + post.id().value()
			+ "\",\"commentId\":\"" + saved.id() + "\",\"authorId\":\"" + command.authorId() + "\"}");
		return CommentView.from(saved);
	}

	@Override
	@Transactional(readOnly = true)
	public CommentsPage getComments(GetCommentsQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		activePost(query.postId());
		var comments = commentRepository.findActiveByPost(query.postId(), before, limit).stream()
			.map(CommentView::from)
			.toList();
		String nextCursor = comments.size() == limit ? cursorCodec.encode(comments.get(comments.size() - 1).createdAt())
			: null;
		return new CommentsPage(comments, nextCursor);
	}

	@Override
	@Transactional
	public PostActionResult react(ReactToPostCommand command) {
		if (command.type() == null) {
			throw new ApiException(400, "INVALID_REACTION_TYPE", "Tipo de reacao obrigatorio.");
		}
		Post post = activePost(command.postId());
		boolean added = postInteractionRepository.addReaction(newId("reaction"), post.id(), command.userId(),
			command.type(), clock.instant());
		if (added) {
			post.addReaction();
			postRepository.save(post);
			outboxEventRepository.append("PostReacted", post.id().value(), "{\"postId\":\"" + post.id().value()
				+ "\",\"authorId\":\"" + command.userId() + "\"}");
		}
		return new PostActionResult(post.id().value(), added ? "REACTED" : "ALREADY_REACTED", post.counters());
	}

	@Override
	@Transactional
	public PostActionResult removeReaction(ReactToPostCommand command) {
		Post post = activePost(command.postId());
		boolean removed = postInteractionRepository.removeReaction(post.id(), command.userId());
		if (removed) {
			post.removeReaction();
			postRepository.save(post);
			outboxEventRepository.append("PostReactionRemoved", post.id().value(), "{\"postId\":\"" + post.id().value()
				+ "\"}");
		}
		return new PostActionResult(post.id().value(), removed ? "REACTION_REMOVED" : "REACTION_NOT_FOUND",
			post.counters());
	}

	@Override
	@Transactional
	public PostActionResult save(PostActionCommand command) {
		Post post = activePost(command.postId());
		boolean added = postInteractionRepository.addSave(newId("saved_post"), post.id(), command.userId(),
			clock.instant());
		if (added) {
			post.addSave();
			postRepository.save(post);
			outboxEventRepository.append("PostSaved", post.id().value(), "{\"postId\":\"" + post.id().value() + "\"}");
		}
		return new PostActionResult(post.id().value(), added ? "SAVED" : "ALREADY_SAVED", post.counters());
	}

	@Override
	@Transactional
	public PostActionResult removeSave(PostActionCommand command) {
		Post post = activePost(command.postId());
		boolean removed = postInteractionRepository.removeSave(post.id(), command.userId());
		if (removed) {
			post.removeSave();
			postRepository.save(post);
			outboxEventRepository.append("PostSaveRemoved", post.id().value(), "{\"postId\":\"" + post.id().value()
				+ "\"}");
		}
		return new PostActionResult(post.id().value(), removed ? "SAVE_REMOVED" : "SAVE_NOT_FOUND", post.counters());
	}

	@Override
	@Transactional
	public PostActionResult share(PostActionCommand command) {
		Post post = activePost(command.postId());
		boolean added = postInteractionRepository.addShare(newId("post_share"), post.id(), command.userId(),
			clock.instant());
		if (added) {
			post.addShare();
			postRepository.save(post);
			outboxEventRepository.append("PostShared", post.id().value(), "{\"postId\":\"" + post.id().value() + "\"}");
		}
		return new PostActionResult(post.id().value(), added ? "SHARED" : "ALREADY_SHARED", post.counters());
	}

	private Post activePost(PostId postId) {
		Post post = postRepository.findById(postId)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		if (!post.isActive()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado.");
		}
		return post;
	}

	private String normalizeContent(String content) {
		if (!StringUtils.hasText(content)) {
			throw new ApiException(400, "INVALID_COMMENT_CONTENT", "Conteudo obrigatorio.");
		}
		String normalized = content.trim();
		if (normalized.length() > 1024) {
			throw new ApiException(400, "COMMENT_TOO_LONG", "Comentario deve ter no maximo 1024 caracteres.");
		}
		return normalized;
	}

	private String normalizeParentComment(String parentCommentId, PostId postId) {
		if (!StringUtils.hasText(parentCommentId)) {
			return null;
		}
		Comment parent = commentRepository.findById(parentCommentId.trim())
			.orElseThrow(() -> new ApiException(404, "PARENT_COMMENT_NOT_FOUND", "Comentario pai nao encontrado."));
		if (!parent.postId().equals(postId) || parent.status() != CommentStatus.ACTIVE) {
			throw new ApiException(404, "PARENT_COMMENT_NOT_FOUND", "Comentario pai nao encontrado.");
		}
		return parent.id();
	}

	private int normalizeLimit(int limit) {
		if (limit < 1) {
			return 20;
		}
		return Math.min(limit, 50);
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}

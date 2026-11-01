package com.socialcoisa.rede.feed.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemoveCommentUseCase;
import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.CommentStatus;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Every way a feed comment leaves the network: the author deleting it, or moderation removing it.
 * Both give the comment back to the post counter, so the counter never drifts.
 */
@Service
public class DeleteCommentService implements ModerationRemoveCommentUseCase {

	private final CommentRepository commentRepository;
	private final PostRepository postRepository;
	private final AuditLogRepository auditLogRepository;

	public DeleteCommentService(CommentRepository commentRepository, PostRepository postRepository,
			AuditLogRepository auditLogRepository) {
		this.commentRepository = commentRepository;
		this.postRepository = postRepository;
		this.auditLogRepository = auditLogRepository;
	}

	/** The author deletes their own comment (hard delete). */
	@Transactional
	public void deleteAsAuthor(UserId userId, String commentId) {
		Comment comment = commentRepository.findById(commentId)
			.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND", "Comentario nao encontrado."));
		if (!comment.authorId().equals(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Voce nao pode deletar este comentario.");
		}
		commentRepository.deleteById(commentId);
		giveBackToPost(comment);
	}

	/**
	 * Moderation removes the comment (soft delete, like a moderated post): it leaves every list, and the
	 * row stays for the audit, the report review and the appeal.
	 */
	@Override
	@Transactional
	public void removeAsModerator(String moderatorId, String commentId, String reason) {
		Comment comment = commentRepository.findById(commentId).orElse(null);
		if (comment == null || comment.status() != CommentStatus.ACTIVE) {
			return;
		}
		commentRepository.markDeleted(comment);
		giveBackToPost(comment);
		auditLogRepository.record(new UserId(moderatorId), "COMMENT_DELETED_BY_MODERATION", commentId,
			StringUtils.hasText(reason) ? reason.trim() : null);
	}

	/**
	 * The post counter gives the removed comment back. Without it, "comment and delete" inflated the
	 * interaction the post received (and the author's credibility) without limit.
	 */
	private void giveBackToPost(Comment comment) {
		if (comment.status() != CommentStatus.ACTIVE) {
			return;
		}
		postRepository.findById(comment.postId()).ifPresent(post -> {
			post.removeComment();
			postRepository.save(post);
		});
	}
}

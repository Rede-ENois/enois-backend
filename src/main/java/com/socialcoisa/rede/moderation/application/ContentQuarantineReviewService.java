package com.socialcoisa.rede.moderation.application;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.ModerationQuarantinePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.moderation.application.port.out.ContentAutomationCheckRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck.ReviewStatus;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;

/**
 * Fila de revisão da quarentena: o que a automação tirou do ar à espera de uma pessoa.
 *
 * <p>Duas saídas possíveis, e só elas: <b>liberar</b> (a automação errou — o post volta ao ar como
 * estava) ou <b>remover</b> (a violação existe — o post sai de vez). Nenhuma das duas pune a conta:
 * punir é decisão de uma pessoa, por um fluxo próprio, com o caso na mão. A automação nunca pune.
 * </p>
 */
@Service
public class ContentQuarantineReviewService {

	private static final Logger LOG = LoggerFactory.getLogger(ContentQuarantineReviewService.class);

	private final ContentAutomationCheckRepository checks;
	private final PostRepository posts;
	private final ModerationQuarantinePostUseCase quarantinePost;
	private final ModerationRemovePostUseCase removePost;
	private final ReportedContentAuthorReader contentAuthors;
	private final UserProfileRepository profiles;
	private final NotificationService notifications;

	public ContentQuarantineReviewService(ContentAutomationCheckRepository checks, PostRepository posts,
			ModerationQuarantinePostUseCase quarantinePost, ModerationRemovePostUseCase removePost,
			ReportedContentAuthorReader contentAuthors, UserProfileRepository profiles,
			NotificationService notifications) {
		this.checks = checks;
		this.posts = posts;
		this.quarantinePost = quarantinePost;
		this.removePost = removePost;
		this.contentAuthors = contentAuthors;
		this.profiles = profiles;
		this.notifications = notifications;
	}

	/** Um caso na fila, com o que a pessoa precisa para decidir. */
	public record PendingView(String postId, String authorId, String authorUsername, String content,
			String verdict, double confidence, String reason, String checkedAt) {
	}

	@Transactional(readOnly = true)
	public List<PendingView> pending(int limit) {
		return checks.findPending(limit).stream().map(this::toView).toList();
	}

	private PendingView toView(ContentAutomationCheck check) {
		Post post = posts.findById(new PostId(check.postId())).orElse(null);
		String content = post == null ? null : post.content();
		String username = profiles.findById(check.authorId()).map(profile -> profile.username()).orElse(null);
		return new PendingView(check.postId(), check.authorId(), username, content, check.verdict().name(),
			check.confidence(), check.reason(), check.checkedAt() == null ? null : check.checkedAt().toString());
	}

	/**
	 * A automação errou: o post volta ao ar. Não é preciso estar em quarentena — se já voltou, é
	 * no-op e o livro-caixa é atualizado do mesmo jeito.
	 */
	@Transactional
	public boolean release(String reviewerId, String postId) {
		Optional<ContentAutomationCheck> registro = checks.findByPost(postId);
		if (registro.isEmpty() || registro.get().status() != ReviewStatus.PENDING) {
			return false;
		}
		quarantinePost.release(reviewerId, postId);
		checks.updateStatus(postId, ReviewStatus.RELEASED);
		authorOf(postId).ifPresent(authorId -> {
			try {
				notifications.create(authorId, "MODERACAO", reviewerId, "Post liberado",
					"Seu post passou pela revisão e voltou ao ar.", "/api/feed", "Ver feed");
			}
			catch (RuntimeException failure) {
				LOG.warn("quarantine_release_notice_failed post={}", postId);
			}
		});
		return true;
	}

	/**
	 * Liberação pedida por OUTRO caminho de revisão (a denúncia que foi arquivada ou respondida com
	 * aviso): o post volta ao ar e o caso sai da fila da quarentena, para não pedir a mesma decisão
	 * duas vezes. Quem avisa o autor aqui é o fluxo que chamou — por isso este método não notifica.
	 */
	@Transactional
	public boolean releaseByReport(String reviewerId, String postId) {
		boolean estavaEmQuarentena = quarantinePost.release(reviewerId, postId);
		checks.findByPost(postId)
			.filter(registro -> registro.status() == ReviewStatus.PENDING)
			.ifPresent(registro -> checks.updateStatus(postId, ReviewStatus.RELEASED));
		return estavaEmQuarentena;
	}

	/** A violação existe: o post sai de vez. A conta NÃO é punida por aqui. */
	@Transactional
	public boolean remove(String reviewerId, String postId) {
		Optional<ContentAutomationCheck> registro = checks.findByPost(postId);
		if (registro.isEmpty() || registro.get().status() != ReviewStatus.PENDING) {
			return false;
		}
		removePost.removeAsModerator(reviewerId, postId,
			"REVIEW: conteudo automatizado (" + registro.get().verdict().name() + ")");
		checks.updateStatus(postId, ReviewStatus.REMOVED);
		authorOf(postId).ifPresent(authorId -> {
			try {
				notifications.create(authorId, "MODERACAO", reviewerId, "Post removido",
					"Seu post passou pela revisão do time e foi removido por descumprir as diretrizes "
						+ "de conteúdo humano da rede. Se discordar, use o canal de recurso.",
					"/api/diretrizes", "Ver diretrizes");
			}
			catch (RuntimeException failure) {
				LOG.warn("quarantine_remove_notice_failed post={}", postId);
			}
		});
		return true;
	}

	private Optional<String> authorOf(String postId) {
		return posts.findById(new PostId(postId)).map(post -> post.authorId().value())
			.or(() -> contentAuthors.authorOf("POST", postId));
	}
}
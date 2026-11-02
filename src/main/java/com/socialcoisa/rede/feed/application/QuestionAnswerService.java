package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Comment;
import com.socialcoisa.rede.feed.domain.CommentStatus;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Duvidas com resposta aceita: quem perguntou marca o comentario que resolveu. A recompensa vai
 * para quem AJUDOU (nunca para popularidade) e sai uma vez por duvida, para sempre: trocar a
 * resposta aceita de um lado para o outro nao paga de novo.
 */
@Service
public class QuestionAnswerService {

	/** Recompensa de "ajuda" (valor-base, escalado pela cotacao; ver docs/COIN_ECONOMY.md). */
	public static final long HELP_REWARD = CoinService.FAIS(20L);

	private static final Logger LOG = LoggerFactory.getLogger(QuestionAnswerService.class);

	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final UserProfileRepository userProfileRepository;
	private final NotificationService notificationService;
	private final CoinService coins;
	private final Clock clock;
	private final TransactionTemplate rewardTransaction;

	public QuestionAnswerService(PostRepository postRepository, CommentRepository commentRepository,
			UserProfileRepository userProfileRepository, NotificationService notificationService, CoinService coins,
			Clock clock, PlatformTransactionManager transactionManager) {
		this.postRepository = postRepository;
		this.commentRepository = commentRepository;
		this.userProfileRepository = userProfileRepository;
		this.notificationService = notificationService;
		this.coins = coins;
		this.clock = clock;
		this.rewardTransaction = new TransactionTemplate(transactionManager);
		this.rewardTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Transactional
	public AcceptedAnswerView accept(PostId postId, UserId actorId, String commentId) {
		Post post = ownQuestion(postId, actorId);
		Comment comment = StringUtils.hasText(commentId)
			? commentRepository.findById(commentId.trim()).orElse(null) : null;
		if (comment == null || !comment.postId().equals(post.id()) || comment.status() != CommentStatus.ACTIVE) {
			throw new ApiException(400, "INVALID_ANSWER_COMMENT", "Esse comentário não é uma resposta desta dúvida.");
		}
		if (post.belongsTo(comment.authorId())) {
			throw new ApiException(400, "OWN_ANSWER", "Você não pode aceitar a sua própria resposta.");
		}
		if (comment.id().equals(post.acceptedCommentId())) {
			return new AcceptedAnswerView(post.id().value(), comment.id(), true);
		}
		postRepository.updateAcceptedAnswer(post.id(), comment.id(), clock.instant());
		String answererId = comment.authorId().value();
		var asker = userProfileRepository.findById(actorId.value()).orElse(null);
		String askerName = asker != null && asker.displayWithNickname() != null ? asker.displayWithNickname() : "Alguém";
		notificationService.create(answererId, "ANSWER_ACCEPTED", actorId.value(), "Sua resposta ajudou",
			askerName + " marcou a sua resposta como a que resolveu a dúvida.",
			"/api/feed/posts/" + post.id().value(), "Ver");
		if (!isNewAccount(answererId) && !isNewAccount(actorId.value())) {
			rewardAfterCommit(answererId, post.id().value());
		}
		return new AcceptedAnswerView(post.id().value(), comment.id(), true);
	}

	@Transactional
	public AcceptedAnswerView unaccept(PostId postId, UserId actorId) {
		Post post = ownQuestion(postId, actorId);
		if (post.acceptedCommentId() != null) {
			postRepository.updateAcceptedAnswer(post.id(), null, null);
		}
		return new AcceptedAnswerView(post.id().value(), null, false);
	}

	private Post ownQuestion(PostId postId, UserId actorId) {
		Post post = postRepository.findById(postId).filter(Post::isActive)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post não encontrado."));
		if (post.type() != PostType.QUESTION) {
			throw new ApiException(400, "NOT_A_QUESTION", "Só dúvidas têm resposta aceita.");
		}
		if (!post.belongsTo(actorId)) {
			throw new ApiException(403, "NOT_QUESTION_AUTHOR", "Só quem perguntou escolhe a resposta.");
		}
		return post;
	}

	/**
	 * Conta com menos de {@link CoinService#NEW_ACCOUNT_WINDOW} (a mesma regra do teto de envio):
	 * nem quem responde nem quem pergunta pode ser conta recem-criada, senao uma conta extra
	 * perguntando vira torneira de faiscas.
	 */
	private boolean isNewAccount(String userId) {
		return userProfileRepository.findById(userId)
			.map(profile -> profile.createdAt() == null
				|| Duration.between(profile.createdAt(), clock.instant()).compareTo(CoinService.NEW_ACCOUNT_WINDOW) < 0)
			.orElse(true);
	}

	/**
	 * A faisca e efeito colateral: roda depois do commit, na propria transacao, e nunca derruba a
	 * aceitacao. Ref por duvida (`resposta:{postId}`) torna o pagamento unico para sempre.
	 */
	private void rewardAfterCommit(String answererId, String postId) {
		Runnable pay = () -> {
			try {
				rewardTransaction.executeWithoutResult(status -> coins.reward(answererId,
					coins.scaledReward(HELP_REWARD), "resposta:" + postId, "Sua resposta resolveu uma dúvida"));
			}
			catch (RuntimeException e) {
				LOG.warn("answer_reward_failed error={}", e.getClass().getSimpleName());
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					pay.run();
				}
			});
			return;
		}
		pay.run();
	}
}

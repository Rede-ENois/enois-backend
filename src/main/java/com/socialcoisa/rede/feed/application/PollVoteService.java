package com.socialcoisa.rede.feed.application;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.VotePollUseCase;
import com.socialcoisa.rede.feed.application.port.out.PollRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.risk.RiskEngine;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.security.BehavioralTimingGuard;
import com.socialcoisa.rede.shared.security.NewAccountCooldown;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Voto da enquete: UMA alternativa por pessoa, e a pessoa pode TROCAR de alternativa (regravando na
 * nova). A unique (post_id, user_id) garante uma linha por pessoa — trocar de voto e UPDATE da
 * linha, nunca um segundo voto. Repetir a MESMA alternativa e idempotente: nao ha escrita, a
 * leitura devolve o estado que ja estava la.
 */
@Service
public class PollVoteService implements VotePollUseCase {

	private final PostRepository postRepository;
	private final PollRepository polls;
	private final AgeGate agePolicy;
	private final SanctionGate sanctionGate;
	private final NewAccountCooldown newAccountCooldown;
	private final UserRateLimiter userRateLimiter;
	private final BehavioralTimingGuard behavioralTimingGuard;
	private final RiskEngine riskEngine;

	public PollVoteService(PostRepository postRepository, PollRepository polls, AgeGate agePolicy,
			SanctionGate sanctionGate, NewAccountCooldown newAccountCooldown, UserRateLimiter userRateLimiter,
			BehavioralTimingGuard behavioralTimingGuard, RiskEngine riskEngine) {
		this.postRepository = postRepository;
		this.polls = polls;
		this.agePolicy = agePolicy;
		this.sanctionGate = sanctionGate;
		this.newAccountCooldown = newAccountCooldown;
		this.userRateLimiter = userRateLimiter;
		this.behavioralTimingGuard = behavioralTimingGuard;
		this.riskEngine = riskEngine;
	}

	@Override
	@Transactional
	public PollView vote(PostId postId, UserId user, int optionPosition) {
		// Votar e escrita como qualquer outra: as mesmas guardas de publicar/comentar.
		agePolicy.requireCanWrite(user.value());
		sanctionGate.requireNotRestricted(user.value());
		newAccountCooldown.require(user.value());
		userRateLimiter.require(user.value(), UserRateLimiter.CREATE_COMMENT);
		behavioralTimingGuard.record(user.value());
		riskEngine.assess(user.value(), "poll_vote", null);
		Post post = postRepository.findById(postId)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		if (!post.isActive()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado.");
		}
		if (post.type() != PostType.POLL) {
			throw new ApiException(400, "NOT_A_POLL", "Este post nao e uma enquete.");
		}
		List<String> options = polls.optionsByPostIds(List.of(post.id().value()))
			.getOrDefault(post.id().value(), List.of());
		if (optionPosition < 0 || optionPosition >= options.size()) {
			throw new ApiException(400, "INVALID_POLL_OPTION", "Alternativa da enquete invalida.");
		}
		Integer previous = polls.votesByUserAndPostIds(user, List.of(post.id().value())).get(post.id().value());
		if (previous != null && previous == optionPosition) {
			// Mesma alternativa: o voto ja esta gravado — devolve o estado sem escrever de novo.
			return view(post.id().value(), options, optionPosition);
		}
		try {
			polls.vote(post.id(), user, optionPosition);
		}
		catch (DataIntegrityViolationException concurrent) {
			// Duas requisicoes da mesma pessoa chegaram juntas e as duas viram "sem voto": uma
			// gravou, a outra bateu na unique. Vale o voto ja gravado; quem vota recarrega o
			// estado real e, se quiser trocar, envia de novo (a troca em si continua aceita).
			throw new ApiException(409, "ALREADY_VOTED", "Voce ja votou nesta enquete.");
		}
		return view(post.id().value(), options, optionPosition);
	}

	/** Contagem por alternativa + total, com o voto gravado marcado em {@code myVote}. */
	private PollView view(String postId, List<String> options, int myVote) {
		Map<Integer, Long> counts = polls.countsByPostIds(List.of(postId)).getOrDefault(postId, Map.of());
		List<Long> perOption = new java.util.ArrayList<>(options.size());
		for (int position = 0; position < options.size(); position++) {
			perOption.add(counts.getOrDefault(position, 0L));
		}
		long total = perOption.stream().mapToLong(Long::longValue).sum();
		return new PollView(options, perOption, myVote, total);
	}
}

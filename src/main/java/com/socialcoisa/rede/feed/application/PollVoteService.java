package com.socialcoisa.rede.feed.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.VotePollUseCase;
import com.socialcoisa.rede.feed.application.port.out.PollRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.PollConfig;
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
 * Voto da enquete: enquanto ela esta aberta, a pessoa escolhe e pode TROCAR a selecao quantas
 * vezes quiser (a escolha unica substitui a anterior; a multipla soma e remove alternativas).
 * Depois do prazo (poll_configs.ends_at) o voto e recusado com 409 POLL_CLOSED e o resultado
 * congela. Votar continua escrita com as mesmas guardas de publicar/comentar.
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
	private final java.time.Clock clock;

	public PollVoteService(PostRepository postRepository, PollRepository polls, AgeGate agePolicy,
			SanctionGate sanctionGate, NewAccountCooldown newAccountCooldown, UserRateLimiter userRateLimiter,
			BehavioralTimingGuard behavioralTimingGuard, RiskEngine riskEngine, java.time.Clock clock) {
		this.postRepository = postRepository;
		this.polls = polls;
		this.agePolicy = agePolicy;
		this.sanctionGate = sanctionGate;
		this.newAccountCooldown = newAccountCooldown;
		this.userRateLimiter = userRateLimiter;
		this.behavioralTimingGuard = behavioralTimingGuard;
		this.riskEngine = riskEngine;
		this.clock = clock;
	}

	@Override
	@Transactional
	public PollView vote(PostId postId, UserId user, Integer option, List<Integer> options) {
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
		String postIdValue = post.id().value();
		PollConfig config = polls.configsByPostIds(List.of(postIdValue)).getOrDefault(postIdValue, PollConfig.SIMPLES);
		if (config.encerrada(clock.instant())) {
			throw new ApiException(409, "POLL_CLOSED", "O prazo desta enquete terminou.");
		}
		List<String> alternativas = polls.optionsByPostIds(List.of(postIdValue)).getOrDefault(postIdValue, List.of());
		List<Integer> escolhas = config.multipleChoice()
			? validarMultipla(alternativas, options)
			: validarUnica(alternativas, option);
		// Troca de voto = substituicao da selecao: grava o que falta e apaga o que saiu.
		polls.replaceVotes(post.id(), user, escolhas);
		return estadoDaEnquete(postIdValue, alternativas, config, user);
	}

	/** Escolha unica: exatamente uma alternativa valida. */
	private List<Integer> validarUnica(List<String> alternativas, Integer option) {
		if (option == null || option < 0 || option >= alternativas.size()) {
			throw new ApiException(400, "INVALID_POLL_OPTION", "Alternativa da enquete invalida.");
		}
		return List.of(option);
	}

	/** Multipla escolha: pelo menos uma alternativa valida, sem repeticao. */
	private List<Integer> validarMultipla(List<String> alternativas, List<Integer> escolhas) {
		List<Integer> limpas = escolhas == null ? List.of()
			: escolhas.stream().filter(java.util.Objects::nonNull).distinct().toList();
		if (limpas.isEmpty()) {
			throw new ApiException(400, "POLL_OPTION_REQUIRED", "Escolha pelo menos uma alternativa.");
		}
		for (Integer escolha : limpas) {
			if (escolha < 0 || escolha >= alternativas.size()) {
				throw new ApiException(400, "INVALID_POLL_OPTION", "Alternativa da enquete invalida.");
			}
		}
		return limpas;
	}

	/** Estado pos-voto: contagens e selecao relidas, para o card atualizar na hora. */
	private PollView estadoDaEnquete(String postIdValue, List<String> alternativas, PollConfig config, UserId user) {
		Map<Integer, Long> counts = polls.countsByPostIds(List.of(postIdValue)).getOrDefault(postIdValue, Map.of());
		List<Long> perOption = new java.util.ArrayList<>(alternativas.size());
		for (int position = 0; position < alternativas.size(); position++) {
			perOption.add(counts.getOrDefault(position, 0L));
		}
		List<Integer> minhas = polls.myVotesByPostIds(user, List.of(postIdValue)).getOrDefault(postIdValue, List.of());
		long total = polls.votersCountByPostIds(List.of(postIdValue)).getOrDefault(postIdValue, 0L);
		return new PollView(alternativas, perOption, minhas, total, config.multipleChoice(), config.endsAt());
	}
}

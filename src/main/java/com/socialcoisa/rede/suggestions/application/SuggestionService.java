package com.socialcoisa.rede.suggestions.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.suggestions.application.port.out.SuggestionIssueGateway;
import com.socialcoisa.rede.suggestions.application.port.out.SuggestionRepository;
import com.socialcoisa.rede.suggestions.domain.Suggestion;
import com.socialcoisa.rede.suggestions.domain.SuggestionStatus;
import com.socialcoisa.rede.suggestions.domain.SuggestionTarget;

/**
 * Sugestões da comunidade. Entram como pendentes; só um moderador aprova, e só então o texto vira
 * issue num repositório público. A issue leva o título e o texto da sugestão e mais nada: nome,
 * e-mail e id de quem sugeriu ficam fora, por construção ({@link #issueBody}).
 */
@Service
public class SuggestionService {

	private static final int MAX_TITLE = 120;
	private static final int MIN_TITLE = 5;
	private static final int MAX_BODY = 4000;
	private static final int MIN_BODY = 20;
	private static final int MAX_PER_DAY = 5;

	/** Breaks mentions and references so the public issue neither pings people nor links elsewhere. */
	private static final String ZERO_WIDTH_SPACE = "\u200B";

	private final SuggestionRepository repository;
	private final SuggestionIssueGateway gateway;
	private final Clock clock;

	public SuggestionService(SuggestionRepository repository, SuggestionIssueGateway gateway, Clock clock) {
		this.repository = repository;
		this.gateway = gateway;
		this.clock = clock;
	}

	public Suggestion submit(String userId, SuggestionTarget target, String title, String body) {
		String cleanTitle = title == null ? "" : title.trim().replaceAll("\\s+", " ");
		String cleanBody = body == null ? "" : body.trim();
		if (target == null) {
			throw new ApiException(400, "INVALID_SUGGESTION", "Escolha onde a sugestão se aplica.");
		}
		if (cleanTitle.length() < MIN_TITLE || cleanTitle.length() > MAX_TITLE) {
			throw new ApiException(400, "INVALID_SUGGESTION", "O título deve ter entre 5 e 120 caracteres.");
		}
		if (cleanBody.length() < MIN_BODY || cleanBody.length() > MAX_BODY) {
			throw new ApiException(400, "INVALID_SUGGESTION", "Descreva a ideia com 20 a 4000 caracteres.");
		}
		Suggestion suggestion = Suggestion.create("sugg_" + UUID.randomUUID().toString().replace("-", ""), userId,
			target, cleanTitle, cleanBody, clock.instant());
		if (!repository.insertWithinLimit(suggestion, clock.instant().minus(Duration.ofDays(1)), MAX_PER_DAY)) {
			throw new ApiException(429, "TOO_MANY_SUGGESTIONS",
				"Você já enviou várias sugestões nas últimas 24h. Tente de novo amanhã.");
		}
		return suggestion;
	}

	public List<Suggestion> list(SuggestionStatus status, int limit) {
		return repository.findFiltered(status, limit < 1 ? 30 : Math.min(limit, 100));
	}

	public List<Suggestion> mine(String userId, int limit) {
		return repository.findByUserId(userId, limit < 1 ? 20 : Math.min(limit, 100));
	}

	/**
	 * Aprova e publica. A decisão é reivindicada de forma atômica ANTES de falar com o GitHub, então
	 * duas aprovações simultâneas nunca abrem duas issues; se o GitHub falhar, a sugestão volta
	 * para a fila. A publicação é idempotente: a issue leva o id da sugestão e, se uma tentativa
	 * anterior chegou a abrir a issue sem confirmar (timeout), a nova aprovação reaproveita aquela.
	 */
	public Suggestion approve(String id, String moderatorId) {
		Suggestion suggestion = find(id);
		switch (gateway.availability(suggestion.target())) {
			case TOKEN_MISSING -> throw new ApiException(503, "SUGGESTIONS_GITHUB_TOKEN_MISSING",
				"O token do GitHub das sugestões não foi configurado neste ambiente.");
			case REPOSITORY_MISSING -> throw new ApiException(503, "SUGGESTIONS_GITHUB_DISABLED",
				"O repositório de destino desta sugestão ainda não foi configurado.");
			case READY -> {
			}
		}
		if (!repository.claimForPublishing(id, moderatorId, clock.instant())) {
			throw new ApiException(409, "SUGGESTION_ALREADY_REVIEWED", "Esta sugestão já foi analisada.");
		}
		var issue = gateway.publish(suggestion.target(), suggestion.id(), issueTitle(suggestion),
			issueBody(suggestion), suggestion.publishAttemptedAt());
		if (issue.isEmpty()) {
			repository.transition(id, SuggestionStatus.PUBLISHED, SuggestionStatus.PENDING, null, null);
			throw new ApiException(502, "GITHUB_UNAVAILABLE",
				"Não foi possível abrir a issue agora. A sugestão continua pendente.");
		}
		return repository.save(find(id).withIssue(issue.get().number(), issue.get().url()));
	}

	public Suggestion reject(String id, String moderatorId) {
		find(id);
		if (!repository.transition(id, SuggestionStatus.PENDING, SuggestionStatus.REJECTED, moderatorId,
				clock.instant())) {
			throw new ApiException(409, "SUGGESTION_ALREADY_REVIEWED", "Esta sugestão já foi analisada.");
		}
		return find(id);
	}

	private Suggestion find(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "SUGGESTION_NOT_FOUND", "Sugestão não encontrada."));
	}

	static String issueTitle(Suggestion suggestion) {
		return neutralizeMentions(suggestion.title());
	}

	/** Só texto da sugestão: autor, e-mail e id internos nunca entram aqui. */
	static String issueBody(Suggestion suggestion) {
		String text = neutralizeMentions(suggestion.body());
		return text + "\n\n---\n_Sugestão enviada de forma anônima pela comunidade do Enois._";
	}

	/**
	 * Keeps the public issue inert: GitHub turns {@code @user} into a notification and
	 * {@code #12}, {@code GH-12}, {@code owner/repo#12} or an issue URL into a cross-reference shown on
	 * the other issue. A zero-width space after the trigger (the approach already used for mentions)
	 * keeps the text readable while GitHub no longer recognises any of them.
	 */
	static String neutralizeMentions(String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		return text.replace("@", "@" + ZERO_WIDTH_SPACE)
			.replaceAll("(?i)(#|\\bGH-)(?=\\d)", "$1" + ZERO_WIDTH_SPACE)
			.replaceAll("(?i)github\\.com", "github" + ZERO_WIDTH_SPACE + ".com");
	}
}

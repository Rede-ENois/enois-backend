package com.socialcoisa.rede.support.application;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.contributions.application.GitHubIdentityService;
import com.socialcoisa.rede.contributions.application.GitHubUserTokenService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.support.application.port.out.GithubIssuesGateway;
import com.socialcoisa.rede.support.application.port.out.SupportRequestRepository;
import com.socialcoisa.rede.support.domain.SupportRequest;
import com.socialcoisa.rede.support.domain.SupportStatus;

/**
 * Espelha o ticket numa issue do repositório PRIVADO de suporte: abre junto com o pedido e
 * fecha/reabre quando o painel muda o status. Roda depois do commit, então uma falha do GitHub
 * nunca desfaz o ticket; o caminho contrário (GitHub -> painel) é o webhook.
 */
@Component
class SupportGithubSync {

	private static final Logger LOG = LoggerFactory.getLogger(SupportGithubSync.class);

	private static final int MAX_ISSUE_TITLE = 200;

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
		.withZone(ZoneId.of("America/Sao_Paulo"));

	private final SupportRequestRepository repository;
	private final GithubIssuesGateway gateway;
	private final SupportThreadService thread;
	private final GitHubIdentityService githubIdentities;
	private final GitHubUserTokenService githubTokens;
	private final UserProfileRepository profiles;

	SupportGithubSync(SupportRequestRepository repository, GithubIssuesGateway gateway,
			SupportThreadService thread, GitHubIdentityService githubIdentities,
			GitHubUserTokenService githubTokens, UserProfileRepository profiles) {
		this.repository = repository;
		this.gateway = gateway;
		this.thread = thread;
		this.githubIdentities = githubIdentities;
		this.githubTokens = githubTokens;
		this.profiles = profiles;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onOpened(SupportRequestOpenedEvent event) {
		if (!gateway.enabled()) {
			return;
		}
		try {
			SupportRequest request = repository.findById(event.requestId()).orElse(null);
			if (request == null || request.githubIssueNumber() != null) {
				return;
			}
			gateway.createIssue(issueTitle(request), issueBody(request))
				.ifPresent(issue -> repository.save(request.withGithubIssue(issue.number(), issue.url())));
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao espelhar o pedido {} no GitHub: {}", event.requestId(),
				failure.getClass().getSimpleName());
		}
	}

	/**
	 * Resposta do painel vira comentário na issue. Publica com o token de escrita da pessoa quando
	 * ela vinculou o GitHub com essa permissão (o comentário aparece com o perfil dela); sem isso,
	 * usa o token de serviço e atribui o texto a quem respondeu. Sempre melhor esforço: falha no
	 * GitHub nunca desfaz a resposta já gravada e enviada ao solicitante.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onReply(SupportReplyEvent event) {
		if (!gateway.enabled()) {
			return;
		}
		try {
			SupportRequest request = repository.findById(event.requestId()).orElse(null);
			if (request == null || request.githubIssueNumber() == null) {
				return;
			}
			String login = githubIdentities.status(event.adminId()).linked()
				? githubIdentities.status(event.adminId()).login() : null;
			String authorName = profiles.findById(event.adminId())
				.map(profile -> profile.displayWithNickname()).orElse(login);
			String body = attribution(login) + "\n\n" + event.body();
			String userToken = githubTokens.accessToken(event.adminId()).orElse(null);
			var comment = gateway.comment(request.githubIssueNumber(), body, userToken);
			if (comment.isEmpty() && userToken != null) {
				// Token da pessoa sem permissão no repositório: cai para o token de serviço.
				comment = gateway.comment(request.githubIssueNumber(), body, null);
			}
			comment.ifPresent(id -> thread.markGithubComment(event.requestId(), event.messageId(), id,
				authorName, login));
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao espelhar a resposta do ticket {} no GitHub: {}", event.requestId(),
				failure.getClass().getSimpleName());
		}
	}

	private static String attribution(String login) {
		return StringUtils.hasText(login)
			? "**@" + login + "** (pelo Énóis):"
			: "**Equipe Énois** (pelo Énóis):";
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onStatusChanged(SupportStatusChangedEvent event) {
		if (!gateway.enabled() || event.previous() == event.current()) {
			return;
		}
		try {
			SupportRequest request = repository.findById(event.requestId()).orElse(null);
			if (request == null || request.githubIssueNumber() == null) {
				return;
			}
			int number = request.githubIssueNumber();
			if (event.current() == SupportStatus.CLOSED) {
				gateway.closeIssue(number);
			}
			else if (event.previous() == SupportStatus.CLOSED) {
				gateway.reopenIssue(number);
			}
		}
		catch (RuntimeException failure) {
			LOG.warn("Falha ao sincronizar o status do pedido {} no GitHub: {}", event.requestId(),
				failure.getClass().getSimpleName());
		}
	}

	String issueTitle(SupportRequest request) {
		String subject = request.subject() == null ? "" : request.subject().trim();
		String title = "[Suporte] " + subject;
		return title.length() <= MAX_ISSUE_TITLE ? title : title.substring(0, MAX_ISSUE_TITLE - 1) + "…";
	}

	/** Corpo em Markdown para o time; o repositório é privado, então o contato pode aparecer. */
	String issueBody(SupportRequest request) {
		StringBuilder body = new StringBuilder();
		body.append("**Ticket:** `").append(request.id()).append("`\n");
		body.append("**Categoria:** ").append(request.category().name()).append("\n");
		body.append("**Solicitante:** `").append(request.userId()).append("`\n");
		if (StringUtils.hasText(request.contactEmail())) {
			body.append("**Contato:** ").append(request.contactEmail()).append("\n");
		}
		body.append("**Aberto em:** ").append(DATE.format(request.createdAt())).append(" (America/Sao_Paulo)\n");
		if (StringUtils.hasText(request.imageUrl())) {
			body.append("**Print:** ").append(request.imageUrl()).append("\n");
		}
		body.append("\n---\n\n").append(request.message() == null ? "" : request.message()).append("\n");
		body.append("\n---\n");
		body.append("Fechar ou reabrir esta issue atualiza o pedido no painel do Énóis (e avisa quem abriu).\n");
		return body.toString();
	}
}

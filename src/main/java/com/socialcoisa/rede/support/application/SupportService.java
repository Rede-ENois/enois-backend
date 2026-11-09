package com.socialcoisa.rede.support.application;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.support.application.port.out.SupportRequestRepository;
import com.socialcoisa.rede.support.domain.SupportCategory;
import com.socialcoisa.rede.support.domain.SupportRequest;
import com.socialcoisa.rede.support.domain.SupportStatus;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Pedidos de suporte do app. O pedido fica registrado (visivel em /admin/support) e, se houver um
 * e-mail de suporte configurado, uma pessoa do time tambem e notificada.
 */
@Service
public class SupportService {

	private static final int MAX_SUBJECT = 160;
	private static final int MAX_MESSAGE = 4000;
	private static final int MIN_MESSAGE = 10;
	private static final int MAX_PER_DAY = 5;

	private final SupportRequestRepository repository;
	private final EmailService emailService;
	private final NotificationService notifications;
	private final com.socialcoisa.rede.shared.security.OutboundUrlGuard outboundUrlGuard;
	private final SupportStatusNotifier statusNotifier;
	private final SupportThreadService thread;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	private final String supportEmail;

	public SupportService(SupportRequestRepository repository, EmailService emailService,
			NotificationService notifications, com.socialcoisa.rede.shared.security.OutboundUrlGuard outboundUrlGuard,
			SupportStatusNotifier statusNotifier, SupportThreadService thread, ApplicationEventPublisher events,
			Clock clock,
			@Value("${rede.support.email:}") String supportEmail) {
		this.repository = repository;
		this.emailService = emailService;
		this.notifications = notifications;
		this.outboundUrlGuard = outboundUrlGuard;
		this.statusNotifier = statusNotifier;
		this.thread = thread;
		this.events = events;
		this.clock = clock;
		this.supportEmail = supportEmail;
	}

	@Transactional
	public SupportRequest open(String userId, String subject, String message, String contactEmail, String imageUrl,
			SupportCategory category) {
		String cleanSubject = subject == null ? "" : subject.trim();
		String cleanMessage = message == null ? "" : message.trim();
		String cleanEmail = StringUtils.hasText(contactEmail) ? contactEmail.trim().toLowerCase(java.util.Locale.ROOT)
			: null;

		if (!StringUtils.hasText(cleanSubject)) {
			throw new ApiException(400, "INVALID_SUPPORT_REQUEST", "Informe o assunto do pedido.");
		}
		if (cleanSubject.length() > MAX_SUBJECT) {
			throw new ApiException(400, "INVALID_SUPPORT_REQUEST", "Assunto deve ter no maximo 160 caracteres.");
		}
		if (cleanMessage.length() < MIN_MESSAGE) {
			throw new ApiException(400, "INVALID_SUPPORT_REQUEST", "Descreva o pedido com pelo menos 10 caracteres.");
		}
		if (cleanMessage.length() > MAX_MESSAGE) {
			throw new ApiException(400, "INVALID_SUPPORT_REQUEST", "Mensagem deve ter no maximo 4000 caracteres.");
		}
		if (cleanEmail == null || !cleanEmail.contains("@")) {
			throw new ApiException(400, "INVALID_SUPPORT_REQUEST", "Informe um e-mail de contato valido.");
		}
		if (repository.countByUserSince(userId, clock.instant().minus(Duration.ofDays(1))) >= MAX_PER_DAY) {
			throw new ApiException(429, "TOO_MANY_SUPPORT_REQUESTS",
				"Voce ja abriu varios pedidos nas ultimas 24h. Aguarde o retorno do time.");
		}

		String cleanImage = imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim();
		if (cleanImage != null && !cleanImage.startsWith("https://")) {
			throw new ApiException(400, "INVALID_SUPPORT_REQUEST", "O anexo deve ser um link https do CDN.");
		}
		if (cleanImage != null && cleanImage.length() > 500) {
			throw new ApiException(400, "INVALID_SUPPORT_REQUEST", "O anexo de imagem deve ter no maximo 500 caracteres.");
		}

		SupportRequest request = repository.save(SupportRequest.create(
			"support_" + UUID.randomUUID().toString().replace("-", ""), userId, cleanSubject, cleanMessage, cleanEmail,
			cleanImage, category, clock.instant()));

		// O aviso por e-mail e o espelho no GitHub nascem DEPOIS do commit (ver SupportEmailNotifier e
		// SupportGithubSync): o envio e SMTP sincrono e nao pode segurar a conexao do banco.
		events.publishEvent(new SupportRequestOpenedEvent(request.id(), request.subject(), request.message(),
			cleanEmail));
		return request;
	}

	@Transactional(readOnly = true)
	public List<SupportRequest> list(SupportCategory category, SupportStatus status, int limit) {
		return repository.findFiltered(category, status, limit < 1 ? 30 : Math.min(limit, 100));
	}

	/** Histórico do próprio solicitante: é onde o fechamento (painel ou GitHub) fica visível. */
	@Transactional(readOnly = true)
	public List<SupportRequest> mine(String userId, int limit) {
		return repository.findByUserId(userId, limit < 1 ? 20 : Math.min(limit, 100));
	}

	/**
	 * Triagem do painel: move o ticket entre aberto/em andamento/resolvido e/ou recategoriza.
	 * Concluir/reabrir avisa quem abriu e, depois do commit, sincroniza a issue do GitHub.
	 */
	@Transactional
	public SupportRequest update(String id, SupportStatus status, SupportCategory category) {
		SupportRequest request = repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "SUPPORT_REQUEST_NOT_FOUND", "Pedido de suporte não encontrado."));
		SupportStatus previous = request.status();
		SupportRequest updated = request;
		if (status != null) {
			updated = updated.withStatus(status);
		}
		if (category != null) {
			updated = updated.withCategory(category);
		}
		SupportRequest saved = repository.save(updated);
		if (status != null && status != previous) {
			if (status == SupportStatus.CLOSED) {
				statusNotifier.closed(saved);
			}
			else if (previous == SupportStatus.CLOSED) {
				statusNotifier.reopened(saved);
			}
			events.publishEvent(new SupportStatusChangedEvent(saved.id(), previous, status));
		}
		return saved;
	}

	/**
	 * Resposta oficial do time ao solicitante: grava o texto, envia por e-mail para o contato e
	 * deixa uma notificação no app (quando o pedido veio de uma conta). O ticket sai de OPEN para
	 * IN_PROGRESS; concluir é uma ação separada (`update` com CLOSED).
	 */
	@Transactional
	public SupportRequest reply(String id, String adminId, String message) {
		String clean = message == null ? "" : message.trim();
		if (clean.length() < 5) {
			throw new ApiException(400, "INVALID_SUPPORT_REPLY",
				"Escreva uma resposta com pelo menos 5 caracteres.");
		}
		if (clean.length() > MAX_MESSAGE) {
			throw new ApiException(400, "INVALID_SUPPORT_REPLY", "A resposta deve ter no máximo 4000 caracteres.");
		}
		SupportRequest request = repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "SUPPORT_REQUEST_NOT_FOUND", "Pedido de suporte não encontrado."));
		SupportRequest withReply = request.withReply(clean, adminId, clock.instant());
		SupportRequest saved = repository.save(
			withReply.status() == SupportStatus.OPEN ? withReply.withStatus(SupportStatus.IN_PROGRESS) : withReply);
		var threadMessage = thread.addFromPanel(saved.id(), clean);
		if (StringUtils.hasText(saved.userId())) {
			notifications.create(saved.userId(), "SUPORTE", adminId, "Resposta do suporte", clean,
				"/suporte", "Ver");
		}
		// O e-mail de resposta sai DEPOIS do commit (SupportEmailNotifier): SMTP sincrono nao pode
		// segurar a conexao do banco.
		events.publishEvent(new SupportReplyEvent(saved.id(), threadMessage.id(), adminId, clean,
			saved.contactEmail(), saved.subject()));
		return saved;
	}

	/** Baixa a imagem do anexo (https, ate 5 MB); falha de download NAO bloqueia o pedido. */
	private byte[] fetchImage(String url) {
		if (url == null || !outboundUrlGuard.safe(url)) {
			return null;
		}
		try {
			var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(java.time.Duration.ofSeconds(8))
				.GET()
				.build();
			var response = java.net.http.HttpClient.newBuilder()
				.connectTimeout(java.time.Duration.ofSeconds(5))
				.build()
				.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
			byte[] bytes = response.body();
			if (response.statusCode() != 200 || bytes == null || bytes.length == 0 || bytes.length > 5 * 1024 * 1024) {
				return null;
			}
			return bytes;
		} catch (Exception e) {
			org.slf4j.LoggerFactory.getLogger(SupportService.class)
				.warn("Falha ao baixar o anexo de imagem do suporte", e);
			return null;
		}
	}

	private String imageName(String url) {
		if (url == null) {
			return "anexo.png";
		}
		String name = url.substring(url.lastIndexOf('/') + 1);
		return name.matches("[A-Za-z0-9._-]{1,80}") ? name : "anexo.png";
	}
}

package com.socialcoisa.rede.blog.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.socialcoisa.rede.blog.application.port.out.BlogSubscriptionRepository;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.security.SecretCipher;

/**
 * Newsletter do blog: quando um post sai, os ASSINANTES recebem um e-mail — no estilo
 * Substack/Ghost, sem credencial de ninguem: o envio sai pelo SMTP do Énóis com o nome do blog
 * ("{blog} via Énóis") e o Reply-To aponta para o e-mail do dono, entao a resposta cai direto
 * na caixa dele.
 *
 * <p>Regras: so no PRIMEIRO publish (editar nao reenvia), so para e-mails verificados, o autor
 * nunca recebe o proprio post, e todo e-mail leva link de descadastro num clique (token cifrado
 * com o segredo da instalacao) + cabecalho List-Unsubscribe. O envio roda DEPOIS do commit e em
 * segundo plano — a publicacao nunca espera o SMTP.</p>
 */
@Service
public class BlogNewsletterService {

	private static final Logger LOG = LoggerFactory.getLogger(BlogNewsletterService.class);

	private static final int PREVIEW_CHARS = 300;

	private final BlogSubscriptionRepository subscriptions;
	private final UserProfileRepository profiles;
	private final EmailService email;
	private final SecretCipher cipher;
	private final TemplateEngine templateEngine;
	private final Executor executor;
	private final String frontendUrl;

	public BlogNewsletterService(BlogSubscriptionRepository subscriptions, UserProfileRepository profiles,
			EmailService email, SecretCipher cipher, TemplateEngine templateEngine,
			Executor blogNewsletterExecutor, @Value("${rede.frontend.url}") String frontendUrl) {
		this.subscriptions = subscriptions;
		this.profiles = profiles;
		this.email = email;
		this.cipher = cipher;
		this.templateEngine = templateEngine;
		this.executor = blogNewsletterExecutor;
		this.frontendUrl = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1)
			: frontendUrl;
	}

	/** Depois do commit: o post existe de verdade; o SMTP roda fora do request. */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onPostPublished(BlogPostPublishedEvent event) {
		try {
			executor.execute(() -> sendToSubscribers(event));
		}
		catch (RejectedExecutionException filaCheia) {
			LOG.warn("Newsletter do blog {} adiada (fila cheia); os assinantes recebem na proxima publicacao.",
				event.blogSlug());
		}
	}

	/** Fan-out em si (tambem chamado direto pelos testes). */
	public void sendToSubscribers(BlogPostPublishedEvent event) {
		if (!cipher.isEnabled()) {
			LOG.warn("Newsletter do blog {} nao enviada: REDE_SECURITY_ENCRYPTION_KEY ausente (token de descadastro).",
				event.blogSlug());
			return;
		}
		List<String> subscriberIds = subscriptions.findSubscriberIds(event.blogId()).stream()
			.filter(id -> !id.equals(event.authorUserId()))
			.toList();
		if (subscriberIds.isEmpty()) {
			return;
		}
		UserProfile author = profiles.findById(event.authorUserId()).orElse(null);
		String authorName = author != null ? author.name() : event.blogSlug();
		String replyTo = author != null && StringUtils.hasText(author.email()) ? author.email() : null;
		String blogTitle = StringUtils.hasText(event.blogTitle()) ? event.blogTitle() : event.blogSlug();
		String postLink = frontendUrl + "/blog/" + event.blogSlug() + "/" + event.postId();
		String preview = previewOf(event.postBody());

		int sent = 0;
		for (UserProfile subscriber : profiles.findAllByIds(subscriberIds)) {
			String to = subscriber.email();
			if (!StringUtils.hasText(to) || !subscriber.emailVerified()) {
				continue; // so e-mail verificado recebe; nao verificados continuam com a notificacao in-app
			}
			try {
				String unsubscribeLink = frontendUrl + "/api/blogs/descadastrar?token="
					+ unsubscribeToken(event.blogId(), subscriber.id());
				Context context = new Context();
				context.setVariable("blogTitle", blogTitle);
				context.setVariable("authorName", authorName);
				context.setVariable("postTitle", event.postTitle());
				context.setVariable("preview", preview);
				context.setVariable("postLink", postLink);
				context.setVariable("unsubscribeLink", unsubscribeLink);
				context.setVariable("currentYear", java.time.Year.now().getValue());
				if (email.sendHtml(to, event.postTitle(), templateEngine.process("email-blog-post", context),
						blogTitle + " via Énóis", replyTo, unsubscribeLink, "BLOG_POST")) {
					sent++;
				}
			}
			catch (RuntimeException perSubscriber) {
				LOG.warn("Falha ao enviar a newsletter do blog {} para {}: {}", event.blogSlug(), subscriber.id(),
					perSubscriber.getMessage());
			}
		}
		LOG.info("Newsletter do blog {}: {} de {} assinantes receberam \"{}\".", event.blogSlug(), sent,
			subscriberIds.size(), event.postTitle());
	}

	/**
	 * Token de descadastro: cifrado com o segredo da instalacao (ninguem forja o de outra pessoa)
	 * e em BASE64 URL-SAFE — o standard tem `+`, `/` e `=`, que se perdem no query string do link.
	 */
	public String unsubscribeToken(String blogId, String userId) {
		String standard = cipher.encrypt(blogId + ":" + userId);
		return java.util.Base64.getUrlEncoder().withoutPadding()
			.encodeToString(java.util.Base64.getDecoder().decode(standard));
	}

	private String decodeUrlSafe(String token) {
		// De volta ao BASE64 PADRAO (texto) que o SecretCipher espera.
		return java.util.Base64.getEncoder().encodeToString(java.util.Base64.getUrlDecoder().decode(token));
	}

	/** Descadastro num clique (link do e-mail, sem sessao). Transação própria: o DELETE é
	 *  `@Modifying` e sem transação o Spring devolvia 400 (erro que o teste pegou). */
	@Transactional
	public boolean unsubscribeByToken(String token) {
		if (!StringUtils.hasText(token) || !cipher.isEnabled()) {
			return false;
		}
		String dados;
		try {
			dados = cipher.decrypt(decodeUrlSafe(token.trim()));
		}
		catch (RuntimeException tokenInvalido) {
			return false;
		}
		int separador = dados.indexOf(':');
		if (separador <= 0 || separador == dados.length() - 1) {
			return false;
		}
		return subscriptions.delete(dados.substring(0, separador), dados.substring(separador + 1)) > 0;
	}

	/** Prévia do corpo do post: sem tags, sem markdown, sem excesso de linhas. */
	public static String previewOf(String body) {
		if (!StringUtils.hasText(body)) {
			return "";
		}
		String texto = body
			.replaceAll("<[^>]+>", " ")
			.replaceAll("!\\[[^\\]]*\\]\\([^\\)]*\\)", " ")
			.replaceAll("\\[([^\\]]*)\\]\\([^\\)]*\\)", "$1")
			.replaceAll("(?m)^#{1,6}\\s*", "")
			.replaceAll("[*_`>~]", "")
			.replaceAll("\\s+", " ")
			.trim();
		return texto.length() > PREVIEW_CHARS ? texto.substring(0, PREVIEW_CHARS).trim() + "…" : texto;
	}
}

package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.in.ModerationQuarantinePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.moderation.ai.ContentAutomationAnalyzer;
import com.socialcoisa.rede.moderation.ai.ContentAutomationAnalyzer.Verdict;
import com.socialcoisa.rede.moderation.application.port.out.ContentAutomationCheckRepository;
import com.socialcoisa.rede.moderation.domain.AutomationSignals;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck;
import com.socialcoisa.rede.moderation.domain.ContentAutomationVerdict;
import com.socialcoisa.rede.notifications.application.NotificationService;

/**
 * Barreira de conteúdo automatizado — a rede é para gente, e esta é a camada que protege isso
 * SEM denúncia.
 *
 * <p><b>Como decide:</b> um marcador determinístico barato (rastro literal de gerador no texto)
 * decide se vale chamar o classificador; quem julga é o modelo, com veredito estruturado. Só
 * "automatizado" com confiança acima do piso aciona a automação.</p>
 *
 * <p><b>O que a automação faz — e o que NUNCA faz:</b> o post entra em QUARENTENA (some do feed,
 * do perfil e do detalhe, mas continua no banco), vira denúncia de sistema na fila de revisão e o
 * autor é avisado. Nada é apagado e ninguém é punido por esta via: quem decide o desfecho —
 * liberar ou remover, e se punir a conta — é sempre uma pessoa, pelos fluxos de revisão que já
 * existem.</p>
 */
@Service
public class ContentAutomationService {

	private static final Logger LOG = LoggerFactory.getLogger(ContentAutomationService.class);

	/** Ator da trilha: é a automação, não uma pessoa. */
	static final String ACTOR = "automod:conteudo";

	private final ContentAutomationCheckRepository checks;
	private final PostRepository posts;
	private final ContentAutomationAnalyzer analyzer;
	private final ModerationQuarantinePostUseCase quarantinePost;
	private final NotificationService notifications;
	private final com.socialcoisa.rede.shared.web.AdminGuard adminGuard;
	private final ModeratorService moderators;
	private final Clock clock;
	private final boolean enabled;

	public ContentAutomationService(ContentAutomationCheckRepository checks, PostRepository posts,
			ContentAutomationAnalyzer analyzer, ModerationQuarantinePostUseCase quarantinePost,
			NotificationService notifications,
			com.socialcoisa.rede.shared.web.AdminGuard adminGuard, ModeratorService moderators, Clock clock,
			@Value("${rede.moderation.automation.enabled:true}") boolean enabled) {
		this.checks = checks;
		this.posts = posts;
		this.analyzer = analyzer;
		this.quarantinePost = quarantinePost;
		this.notifications = notifications;
		this.adminGuard = adminGuard;
		this.moderators = moderators;
		this.clock = clock;
		this.enabled = enabled;
	}

	public boolean enabled() {
		return enabled;
	}

	/** O que aconteceu com um post (para log e teste). */
	public record Outcome(String status, ContentAutomationVerdict verdict, double confidence, boolean quarantined) {
	}

	/**
	 * Avalia UM post. Idempotente: já avaliado, devolve o registro anterior sem chamar o modelo.
	 *
	 * <p>Nunca lança para o chamador: quem chama roda depois do commit da publicação, e uma falha
	 * aqui não pode derrubar a thread do executor nem afetar o post no ar.</p>
	 */
	public Outcome evaluate(String postId) {
		if (!enabled) {
			return new Outcome("DISABLED", ContentAutomationVerdict.UNSURE, 0, false);
		}
		Optional<ContentAutomationCheck> anterior = checks.findByPost(postId);
		if (anterior.isPresent()) {
			return new Outcome("ALREADY_CHECKED", anterior.get().verdict(), anterior.get().confidence(),
				anterior.get().status() == ContentAutomationCheck.ReviewStatus.PENDING);
		}
		Post post = posts.findById(new com.socialcoisa.rede.feed.domain.PostId(postId)).orElse(null);
		if (post == null || !post.isActive() || post.content() == null) {
			return new Outcome("SKIP", ContentAutomationVerdict.UNSURE, 0, false);
		}
		// Equipe fora: um falso positivo escondendo o conteúdo de quem cuida da rede seria pior
		// que qualquer bot.
		if (adminGuard.isAdmin(new com.socialcoisa.rede.shared.web.CurrentUser(post.authorId()))
				|| moderators.isModerator(post.authorId().value())) {
			return new Outcome("STAFF_SKIPPED", ContentAutomationVerdict.UNSURE, 0, false);
		}

		if (!AutomationSignals.suspect(post.content())) {
			// Registra sem custo de modelo: é o que impede a varredura de reler este post para sempre.
			record(post, ContentAutomationVerdict.HUMAN, 0, "sem rastro de gerador no texto",
				ContentAutomationCheck.ReviewStatus.NONE);
			return new Outcome("CLEAN_DETERMINISTIC", ContentAutomationVerdict.HUMAN, 0, false);
		}

		Optional<Verdict> veredito = analyzer.analyze(post.content());
		if (veredito.isEmpty()) {
			record(post, ContentAutomationVerdict.UNSURE, 0, "sem palpite automatico",
				ContentAutomationCheck.ReviewStatus.NONE);
			return new Outcome("NO_VERDICT", ContentAutomationVerdict.UNSURE, 0, false);
		}
		Verdict decisao = veredito.get();
		record(post, decisao.verdict(), decisao.confidence(), decisao.reason(),
			ContentAutomationCheck.ReviewStatus.NONE);
		LOG.info("content_automation post={} veredito={} confianca={}", postId, decisao.verdict(),
			decisao.confidence());

		if (!ContentAutomationAnalyzer.canQuarantine(decisao)) {
			return new Outcome("RECORDED", decisao.verdict(), decisao.confidence(), false);
		}
		return quarantine(post, decisao);
	}

	/**
	 * Precaução, não punição: o post sai do ar, a fila de revisão recebe o caso e o autor fica
	 * sabendo. Nenhum caminho aqui aplica sanção.
	 */
	private Outcome quarantine(Post post, Verdict decisao) {
		String motivo = "Automod de conteudo automatizado: " + decisao.reason();
		// Antes de esconder: o caso passa a existir na fila de revisão. Se a quarentena falhar logo
		// abaixo, o post continua no ar mas o caso já está com uma pessoa.
		record(post, decisao.verdict(), decisao.confidence(), decisao.reason(),
			ContentAutomationCheck.ReviewStatus.PENDING);
		boolean escondido = false;
		try {
			escondido = quarantinePost.quarantine(ACTOR, post.id().value(), motivo);
		}
		catch (RuntimeException failure) {
			LOG.warn("content_automation_quarantine_failed post={} motivo={}", post.id().value(),
				failure.getClass().getSimpleName());
		}
		if (!escondido) {
			// Quarentena falhou: o caso continua na fila de revisão mesmo assim (denúncia de
			// sistema), só que com o post no ar até a decisão.
			LOG.warn("content_automation_quarantine_missed post={}", post.id().value());
		}
		try {
			notifications.create(post.authorId().value(), "MODERACAO", ACTOR, "Post em análise",
				"Um post seu entrou em revisão automática e está temporariamente fora do ar. Uma pessoa "
					+ "do time de moderação vai revisar e você é avisado do resultado; se estiver tudo "
					+ "certo, ele volta ao ar.",
				"/api/diretrizes", "Ver diretrizes");
		}
		catch (RuntimeException failure) {
			LOG.warn("content_automation_notice_failed post={} motivo={}", post.id().value(),
				failure.getClass().getSimpleName());
		}
		LOG.warn("content_automation_quarantinado post={} confianca={}", post.id().value(), decisao.confidence());
		return new Outcome("QUARANTINED", decisao.verdict(), decisao.confidence(), true);
	}

	private void record(Post post, ContentAutomationVerdict verdict, double confidence, String reason,
			ContentAutomationCheck.ReviewStatus status) {
		try {
			checks.save(ContentAutomationCheck.of(post.id().value(), post.authorId().value(), verdict,
				confidence, reason, analyzer.modelName(), clock.instant(), status));
		}
		catch (RuntimeException failure) {
			// Ledger indisponível não pode impedir a barreira de agir (a checagem pode repetir).
			LOG.warn("content_automation_ledger_failed post={} motivo={}", post.id().value(),
				failure.getClass().getSimpleName());
		}
	}

	/** Ids de posts recentes ainda sem avaliação (a varredura usa isto). */
	public java.util.List<String> pending(java.time.Instant since, int limit) {
		return checks.findUncheckedPostIdsSince(since, limit);
	}

	/** Auxiliar de texto usado pelo worker: vazia quando o post já saiu do ar. */
	public boolean isActive(String postId) {
		return posts.findById(new com.socialcoisa.rede.feed.domain.PostId(postId))
			.map(post -> post.isActive() && StringUtils.hasText(post.content()))
			.orElse(false);
	}
}
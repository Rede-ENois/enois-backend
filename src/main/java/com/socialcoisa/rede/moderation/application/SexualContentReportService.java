package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.feed.application.port.in.ModerationQuarantinePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.moderation.ai.ModerationAiService;
import com.socialcoisa.rede.moderation.application.port.out.ContentAutomationCheckRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedMediaReader;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck;
import com.socialcoisa.rede.moderation.domain.ContentAutomationCheck.ReviewStatus;
import com.socialcoisa.rede.moderation.domain.ContentAutomationVerdict;
import com.socialcoisa.rede.notifications.application.NotificationService;

import tools.jackson.databind.JsonNode;

/**
 * Denúncia por conteúdo SEXUAL: o post sai do ar na hora e quem decide primeiro é o bot.
 *
 * <p><b>Por que este caminho existe:</b> conteúdo sexual é o caso em que a espera por uma pessoa é
 * o dano. A denúncia comum continua indo para a fila humana; esta, assim que é criada, esconde o
 * post (quarentena, não apagamento) e dispara a avaliação automática com o texto e as imagens do
 * próprio post.</p>
 *
 * <p><b>As três saídas, e só elas:</b> NSFW confirmado (veredito PENALIZAR que passa no gate do
 * {@link ModerationAiService#canAutoPenalize}) apaga o post de vez; SAFE (veredito NENHUMA) devolve
 * ao feed como estava; qualquer outra coisa — dúvida, modelo fora do ar, JSON inválido — deixa o
 * caso na fila humana da quarentena. Nenhuma saída pune a conta: apagar conteúdo e punir conta são
 * decisões separadas, e a segunda continua sendo de uma pessoa.</p>
 */
@Service
public class SexualContentReportService {

	private static final Logger LOG = LoggerFactory.getLogger(SexualContentReportService.class);

	/** Ator da trilha: é a automação, não uma pessoa. */
	static final String ACTOR = "automod:sexual";
	private static final int MAX_IMAGES = 3;

	private final ContentAutomationCheckRepository checks;
	private final PostRepository posts;
	private final ModerationQuarantinePostUseCase quarantinePost;
	private final ModerationRemovePostUseCase removePost;
	private final ReportedMediaReader mediaReader;
	private final ModerationAiService ai;
	private final NotificationService notifications;
	private final Clock clock;
	private final boolean enabled;

	public SexualContentReportService(ContentAutomationCheckRepository checks, PostRepository posts,
			ModerationQuarantinePostUseCase quarantinePost, ModerationRemovePostUseCase removePost,
			ReportedMediaReader mediaReader, ModerationAiService ai, NotificationService notifications,
			Clock clock, @Value("${rede.moderation.sexual.enabled:true}") boolean enabled) {
		this.checks = checks;
		this.posts = posts;
		this.quarantinePost = quarantinePost;
		this.removePost = removePost;
		this.mediaReader = mediaReader;
		this.ai = ai;
		this.notifications = notifications;
		this.clock = clock;
		this.enabled = enabled;
	}

	public boolean enabled() {
		return enabled;
	}

	/** O que aconteceu com o post (para log e teste). */
	public record Outcome(String status, boolean quarantined, boolean removed, boolean released) {
	}

	private static final Outcome SKIPPED = new Outcome("SKIP", false, false, false);

	/**
	 * Trata uma denúncia sexual recém-criada. Idempotente: post já avaliado por este caminho
	 * devolve o estado anterior sem chamar o modelo de novo.
	 *
	 * <p>A quarentena é a PRIMEIRA coisa (precaução imediata); a avaliação do bot vem depois e não
	 * pode derrubar nada: falha vira "dúvida" e o caso fica com uma pessoa.</p>
	 */
	public Outcome handle(String postId, String reportDescription) {
		if (!enabled) {
			return new Outcome("DISABLED", false, false, false);
		}
		Optional<ContentAutomationCheck> anterior = checks.findByPost(postId);
		if (anterior.isPresent()) {
			return new Outcome("ALREADY_CHECKED", anterior.get().status() == ReviewStatus.PENDING,
				anterior.get().status() == ReviewStatus.REMOVED, anterior.get().status() == ReviewStatus.RELEASED);
		}
		Post post = posts.findById(new PostId(postId)).orElse(null);
		if (post == null || !post.isActive()) {
			return SKIPPED;
		}

		// 1. Precaução imediata: o post sai do ar e o caso entra na fila humana ANTES do bot.
		record(post, ContentAutomationVerdict.UNSURE, 0, "denúncia sexual: em avaliação automática",
			ReviewStatus.PENDING);
		boolean escondido = false;
		try {
			escondido = quarantinePost.quarantine(ACTOR, postId, "Denúncia sexual em avaliação (precaução)");
		}
		catch (RuntimeException failure) {
			LOG.warn("sexual_report_quarantine_failed post={} motivo={}", postId,
				failure.getClass().getSimpleName());
		}
		notify(post, "Post em análise",
			"Um post seu foi denunciado por conteúdo sexual e está temporariamente fora do ar enquanto "
				+ "passa pela avaliação automática e pela revisão do time. Se estiver tudo certo, ele volta "
				+ "ao ar e você é avisado.", "/api/diretrizes", "Ver diretrizes");

		// 2. O bot decide: NSFW confirmado apaga, seguro devolve, o resto fica com uma pessoa.
		if (!ai.available()) {
			LOG.info("sexual_report_bot_unavailable post={} (segue para revisão humana)", postId);
			return new Outcome("HUMAN_REVIEW", escondido, false, false);
		}
		Optional<JsonNode> palpite;
		try {
			List<byte[]> imagens = mediaReader.imagesOf("POST", postId, MAX_IMAGES);
			palpite = ai.triage(post.content() == null ? "" : post.content(), "SEXUAL_CONTENT",
				reportDescription == null ? "" : reportDescription, imagens)
				.flatMap(ai::parseVerdict);
		}
		catch (RuntimeException failure) {
			LOG.warn("sexual_report_bot_failed post={} motivo={}", postId, failure.getClass().getSimpleName());
			return new Outcome("HUMAN_REVIEW", escondido, false, false);
		}
		if (palpite.isEmpty()) {
			return new Outcome("HUMAN_REVIEW", escondido, false, false);
		}
		JsonNode veredito = palpite.get();
		String acao = veredito.path("acao").asString();
		if ("PENALIZAR".equals(acao) && ModerationAiService.canAutoPenalize(veredito)) {
			return remove(post, veredito);
		}
		if ("NENHUMA".equals(acao)) {
			return release(post, veredito);
		}
		return new Outcome("HUMAN_REVIEW", escondido, false, false);
	}

	/** NSFW confirmado: o post sai de vez. A conta NÃO é punida por este caminho. */
	private Outcome remove(Post post, JsonNode veredito) {
		String justificativa = veredito.path("justificativa").asString();
		String motivo = "Automod de conteúdo sexual: "
			+ (justificativa.isBlank() ? "confirmado pela avaliação automática" : justificativa);
		try {
			removePost.removeAsModerator(ACTOR, post.id().value(), motivo);
		}
		catch (RuntimeException failure) {
			// A remoção falhou: o post continua escondido e o caso NÃO sai da fila humana.
			LOG.error("sexual_report_remove_failed post={} motivo={}", post.id().value(),
				failure.getClass().getSimpleName());
			return new Outcome("REMOVE_FAILED", true, false, false);
		}
		checks.updateStatus(post.id().value(), ReviewStatus.REMOVED);
		notify(post, "Post removido",
			"Seu post foi removido por conteúdo sexual, depois da avaliação automática e da revisão do "
				+ "time. Se discordar, use o canal de recurso.", "/api/diretrizes", "Ver diretrizes");
		LOG.warn("sexual_report_removido post={} confianca={}", post.id().value(),
			veredito.path("confianca").asDouble(0));
		return new Outcome("REMOVED", true, true, false);
	}

	/** Seguro: o post volta ao feed exatamente como estava. */
	private Outcome release(Post post, JsonNode veredito) {
		boolean voltou = false;
		try {
			voltou = quarantinePost.release(ACTOR, post.id().value());
		}
		catch (RuntimeException failure) {
			LOG.warn("sexual_report_release_failed post={} motivo={}", post.id().value(),
				failure.getClass().getSimpleName());
		}
		checks.updateStatus(post.id().value(), ReviewStatus.RELEASED);
		notify(post, "Post liberado",
			"Seu post passou pela avaliação automática e voltou ao ar.", "/api/feed", "Ver feed");
		LOG.info("sexual_report_liberado post={} confianca={}", post.id().value(),
			veredito.path("confianca").asDouble(0));
		return new Outcome("RELEASED", false, false, voltou);
	}

	private void record(Post post, ContentAutomationVerdict verdict, double confidence, String reason,
			ReviewStatus status) {
		try {
			checks.save(ContentAutomationCheck.of(post.id().value(), post.authorId().value(), verdict,
				confidence, reason, ai.model(), clock.instant(), status));
		}
		catch (RuntimeException failure) {
			// Ledger indisponível não pode impedir a precaução: a quarentena segue.
			LOG.warn("sexual_report_ledger_failed post={} motivo={}", post.id().value(),
				failure.getClass().getSimpleName());
		}
	}

	private void notify(Post post, String title, String message, String href, String cta) {
		try {
			notifications.create(post.authorId().value(), "MODERACAO", ACTOR, title, message, href, cta);
		}
		catch (RuntimeException failure) {
			LOG.warn("sexual_report_notice_failed post={} motivo={}", post.id().value(),
				failure.getClass().getSimpleName());
		}
	}
}

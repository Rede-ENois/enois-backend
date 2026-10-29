package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.moderation.ai.MediaSafetyAnalyzer;
import com.socialcoisa.rede.moderation.application.port.out.MediaSafetyCheckRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedMediaReader;
import com.socialcoisa.rede.moderation.domain.MediaSafetyCheck;
import com.socialcoisa.rede.moderation.domain.MediaSafetyVerdict;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Barreira automática de segurança para a mídia enviada.
 *
 * <p><b>Por que existe:</b> a moderação é reativa — só olha o que alguém denuncia — e a triagem por
 * IA recebia apenas o TEXTO do alvo. Publicação cujo conteúdo está na imagem, e não na legenda,
 * ficava sem sinal nenhum: nenhum motivo adequado, nenhum peso na pré-triagem e nada para o modelo
 * julgar. O upload também não passa pelo backend (o navegador envia direto ao storage por URL
 * assinada), então nenhum ponto do caminho olhava o arquivo. Esta barreira fecha esse buraco.</p>
 *
 * <p><b>Como decide:</b> o analisador olha a imagem e devolve um veredito estruturado. Só
 * {@link MediaSafetyVerdict#EXPLICIT} e {@link MediaSafetyVerdict#SEXUAL_MINOR} com confiança
 * suficiente acionam a automação; o resto fica registrado e vai para revisão humana. O modelo nunca
 * apaga nem bane — quem faz isso é este serviço, e a conta sempre pode recorrer.</p>
 *
 * <p><b>O que a violação custa:</b> a mídia sai do ar em todos os posts que a usam e a conta é
 * banida na hora. Não é restrição com prazo: o conteúdo é irrecuperável na prática (fica no bucket,
 * não na timeline) e a diretriz da casa para esse tipo de conteúdo é expulsão, não advertência. O
 * recurso continua existindo pelo fluxo normal de penalidade, para o caso de erro.</p>
 */
@Service
public class MediaSafetyService {

	private static final Logger LOG = LoggerFactory.getLogger(MediaSafetyService.class);

	/** Ator da trilha: é a automação, não uma pessoa. */
	static final String ACTOR = "automod:midia";

	private final MediaSafetyCheckRepository checks;
	private final ReportedMediaReader mediaReader;
	private final MediaSafetyAnalyzer analyzer;
	private final MediaAssetRepository assets;
	private final PostRepository posts;
	private final ModerationRemovePostUseCase removePost;
	private final ModeratorService moderators;
	private final UserProfileRepository profiles;
	private final Clock clock;
	private final boolean enabled;

	public MediaSafetyService(MediaSafetyCheckRepository checks, ReportedMediaReader mediaReader,
			MediaSafetyAnalyzer analyzer, MediaAssetRepository assets, PostRepository posts,
			ModerationRemovePostUseCase removePost, ModeratorService moderators, UserProfileRepository profiles,
			Clock clock, @Value("${rede.moderation.media.enabled:true}") boolean enabled) {
		this.checks = checks;
		this.mediaReader = mediaReader;
		this.analyzer = analyzer;
		this.assets = assets;
		this.posts = posts;
		this.removePost = removePost;
		this.moderators = moderators;
		this.profiles = profiles;
		this.clock = clock;
		this.enabled = enabled;
	}

	public boolean enabled() {
		return enabled;
	}

	/** O que aconteceu com uma mídia (para log e teste). */
	public record Outcome(String status, MediaSafetyVerdict verdict, double confidence, int removedPosts,
			String sanction) {
	}

	/**
	 * Checa UMA mídia. Idempotente: já checada, devolve o registro anterior sem chamar o modelo.
	 *
	 * <p>Não é transacional de propósito — o download e a chamada ao modelo levam segundos, e
	 * segurar uma transação aberta por isso trava linha de `media_assets` sem necessidade. Cada
	 * efeito (registro, remoção, sanção) tem sua própria transação.</p>
	 */
	public Outcome check(String mediaId) {
		if (!enabled) {
			return new Outcome("DISABLED", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}
		Optional<MediaSafetyCheck> previous = checks.findById(mediaId);
		if (previous.isPresent()) {
			return new Outcome("ALREADY_CHECKED", previous.get().verdict(), previous.get().confidence(), 0, null);
		}
		Optional<MediaAsset> asset = assets.findById(mediaId);
		if (asset.isEmpty()) {
			return new Outcome("UNKNOWN_MEDIA", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}
		String ownerId = asset.get().ownerId().value();

		List<byte[]> images = mediaReader.imagesOf("MEDIA", mediaId, 1);
		Optional<MediaSafetyAnalyzer.Verdict> verdict = images.isEmpty()
			? Optional.empty()
			: analyzer.analyze(images.get(0));
		if (verdict.isEmpty()) {
			// Sem palpite (modelo fora do ar, imagem ilegível): registra como UNSURE para não
			// repetir o download a cada rodada e deixa para a revisão humana.
			record(mediaId, ownerId, MediaSafetyVerdict.UNSURE, "LOW", 0, "sem palpite automatico");
			return new Outcome("NO_VERDICT", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}

		MediaSafetyAnalyzer.Verdict decision = verdict.get();
		record(mediaId, ownerId, decision.verdict(), decision.severity(), decision.confidence(), decision.reason());
		LOG.info("media_safety mediaId={} owner={} veredito={} confianca={} gravidade={}", mediaId, ownerId,
			decision.verdict(), decision.confidence(), decision.severity());

		if (!MediaSafetyAnalyzer.canActAlone(decision)) {
			return new Outcome("RECORDED", decision.verdict(), decision.confidence(), 0, null);
		}
		return enforce(mediaId, ownerId, decision);
	}

	/**
	 * Tira a mídia do ar em todos os posts que a usam e bane a conta.
	 *
	 * <p>A remoção acontece ANTES da sanção: mesmo que a sanção falhe (usuário já banido, perfil
	 * sumiu), a imagem não pode continuar na timeline.</p>
	 */
	private Outcome enforce(String mediaId, String ownerId, MediaSafetyAnalyzer.Verdict decision) {
		String reason = "Automod de midia: " + decision.reason();
		int removed = 0;
		for (Post post : posts.findByMediaId(mediaId)) {
			try {
				removePost.removeAsModerator(ACTOR, post.id().value(), reason);
				removed++;
			}
			catch (RuntimeException failure) {
				LOG.warn("media_safety_remove_failed post={} motivo={}", post.id().value(),
					failure.getClass().getSimpleName());
			}
		}

		String sanction = "NONE";
		UserProfile author = profiles.findById(ownerId).orElse(null);
		if (author != null) {
			try {
				// PERMABAN: conteúdo sexual explícito não volta com advertência. O recurso segue
				// existindo pelo fluxo normal, para o caso de o modelo ter errado.
				var result = moderators.sanctionUser(ACTOR, author.username(), SanctionType.PERMABAN, reason);
				sanction = result.status();
				if (!"ALREADY_BANNED".equals(result.status())) {
					moderators.sanitizeUser(ACTOR, author.username(), reason);
				}
				LOG.warn("media_safety_ban username={} mediaId={} veredito={} posts={}", author.username(), mediaId,
					decision.verdict(), removed);
			}
			catch (RuntimeException failure) {
				sanction = "FAILED";
				LOG.error("media_safety_ban_failed username={} mediaId={} motivo={}", author.username(), mediaId,
					failure.getClass().getSimpleName());
			}
		}
		return new Outcome("ENFORCED", decision.verdict(), decision.confidence(), removed, sanction);
	}

	private void record(String mediaId, String ownerId, MediaSafetyVerdict verdict, String severity, double confidence,
			String reason) {
		try {
			checks.save(MediaSafetyCheck.of(mediaId, ownerId, verdict, severity, confidence, reason,
				analyzer.modelName(), clock.instant()));
		}
		catch (RuntimeException failure) {
			// Ledger indisponível não pode impedir a barreira de agir.
			LOG.warn("media_safety_ledger_failed mediaId={} motivo={}", mediaId,
				failure.getClass().getSimpleName());
		}
	}

	/** Ids de mídia recentes ainda sem checagem (a varredura usa isto). */
	public List<String> pending(Instant since, int limit) {
		return checks.findUncheckedMediaIdsSince(since, limit);
	}
}

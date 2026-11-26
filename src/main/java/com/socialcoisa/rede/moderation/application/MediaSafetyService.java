package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.colagem.application.port.in.ColagemModerationPort;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPageCatalog.PageRef;
import com.socialcoisa.rede.moderation.application.SafetyPorts.AnalysedPieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.DocumentPieces;
import com.socialcoisa.rede.moderation.application.SafetyPorts.SafetyLedger;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaDeleteStatus;
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
	private final ColagemModerationPort colagem;
	private final ModeratorService moderators;
	private final UserProfileRepository profiles;
	/** A mídia como o classificador a vê: imagem, ou as páginas do PDF. */
	private final AnalysedPieces pieces;
	/** O cadastro das peças, para saber o dono de uma mídia sem resultado gravado, e a fila de análise. */
	private final MediaSafetyPendingChecks documents;
	/** O cadastro das peças: dono da mídia sem resultado gravado. */
	private final SafetyLedger ledger;
	/** A barreira sobre o documento inteiro: uma folha barrada leva todas as outras. */
	private final DocumentPieces retirement;
	private final Clock clock;
	private final boolean enabled;

	public MediaSafetyService(MediaSafetyCheckRepository checks, ReportedMediaReader mediaReader,
			MediaSafetyAnalyzer analyzer, MediaAssetRepository assets, PostRepository posts,
			ModerationRemovePostUseCase removePost, ColagemModerationPort colagem, ModeratorService moderators,
			UserProfileRepository profiles, AnalysedPieces pieces,
			MediaSafetyPendingChecks documents, DocumentPieces retirement, SafetyLedger ledger, Clock clock,
			@Value("${rede.moderation.media.enabled:true}") boolean enabled) {
		this.checks = checks;
		this.mediaReader = mediaReader;
		this.analyzer = analyzer;
		this.assets = assets;
		this.posts = posts;
		this.removePost = removePost;
		this.colagem = colagem;
		this.moderators = moderators;
		this.profiles = profiles;
		this.pieces = pieces;
		this.documents = documents;
		this.retirement = retirement;
		this.ledger = ledger;
		this.clock = clock;
		this.enabled = enabled;
	}

	public boolean enabled() {
		return enabled;
	}

	/**
	 * Manda analisar o que as pontas pediram (render da subida, render da varredura). Devolve quantas
	 * mídias foram barradas: a barreira pode cair aqui, e a varredura conta o que ela barrou.
	 */
	public int analyseQueued() {
		return enabled ? documents.analyseQueued() : 0;
	}

	/** O que aconteceu com uma mídia (para log e teste). */
	public record Outcome(String status, MediaSafetyVerdict verdict, double confidence, int removedPosts,
			String sanction) {
	}

	/**
	 * Checa UMA mídia. A idempotência é por peça: peça com veredito gravado não volta ao modelo; peça
	 * que gravou EXPLICIT ou SEXUAL_MINOR passa pela barreira de novo se ainda não estiver retirada,
	 * porque registro no ledger não é takedown e a varredura é quem fecha essa conta.
	 *
	 * <p>O que se checa é a PEÇA: para imagem, a própria; para PDF, cada página renderizada. PDF sem
	 * página não é "nada para ver" — é documento que ninguém olhou; um registro sem veredito guarda
	 * esse fato, e o catálogo do portfólio não libera mídia sem veredito.</p>
	 *
	 * <p>Não é transacional de propósito — o download e a chamada ao modelo levam segundos, e
	 * segurar uma transação aberta por isso trava linha de `media_assets` sem necessidade. Cada
	 * efeito (registro, remoção, sanção) tem sua própria transação.</p>
	 */
	public Outcome check(String mediaId) {
		if (!enabled) {
			return new Outcome("DISABLED", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}
		// A varredura pode chegar pela folha de um PDF (a chave dela é imagem, e é ela que o ledger
		// guarda). Folha sozinha não é mídia: quem precisa de decisão é o documento dela. Sem
		// documento (folha solta) a folha é tratada como qualquer imagem.
		String document = pieces.documentOf(mediaId).orElse(mediaId);
		if (!document.equals(mediaId)) {
			return check(document);
		}
		List<PageRef> views = pieces.piecesOf(mediaId);
		if (isWithdrawn(mediaId)) {
			return new Outcome("WITHDRAWN", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}
		// Mídia de uma peça só (imagem): a resposta é a decisão da própria mídia.
		if (views.size() == 1 && mediaId.equals(views.get(0).mediaId())) {
			return outcomesOfSinglePiece(mediaId);
		}
		// Documento sem página para olhar: registra o fato, sem veredito, e não libera.
		if (views.isEmpty()) {
			recordUnseen(mediaId);
			return new Outcome("NO_VIEW", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}
		// Toda folha é olhada antes de a barreira rodar: uma folha barrada não pode deixar as seguintes
		// sem veredito. A barreira segue uma por documento.
		List<Outcome> outcomes = views.stream().map(page -> outcomeOf(page, mediaId)).toList();
		Outcome worst = null;
		for (Outcome outcome : outcomes) {
			// A peça mais grave decide o documento — passou pelo modelo agora ou veio do ledger —, e
			// o empate fica com a que esta passada decidiu.
			if (worst == null || severityOf(outcome) > severityOf(worst)) {
				worst = outcome;
			}
		}
		// A resposta é a da barreira: o resultado precisa refletir o takedown.
		for (Outcome outcome : outcomes) {
			if ("ENFORCED".equals(outcome.status())) {
				return outcome;
			}
		}
		return worst == null ? new Outcome("ALREADY_CHECKED", MediaSafetyVerdict.UNSURE, 0, 0, null) : worst;
	}

	/**
	 * A peça já foi tirada do ar: conta como vista, sem devolver folha ao storage nem veredito novo.
	 *
	 * <p>Pedido de remoção não é remoção: entre a barreira e o worker de limpeza as folhas continuam
	 * no ar, e o documento só está fora quando o objeto saiu.</p>
	 */
	private boolean isWithdrawn(String mediaId) {
		return assets.findById(mediaId).map(asset -> asset.deleteStatus() == MediaDeleteStatus.OBJECT_DELETED)
			.orElse(false);
	}

	/** Imagem: o registro é a resposta; sem palpite do classificador ele guarda UNSURE, e é ele que vale. */
	private Outcome outcomesOfSinglePiece(String mediaId) {
		Optional<MediaSafetyCheck> previous = checks.findById(mediaId);
		if (previous.isPresent()) {
			return new Outcome("ALREADY_CHECKED", previous.get().verdict(), previous.get().confidence(), 0, null);
		}
		Outcome fresh = checkPiece(mediaId, mediaId);
		Optional<MediaSafetyCheck> recorded = checks.findById(mediaId);
		return recorded.isEmpty() || "ENFORCED".equals(fresh.status()) ? fresh
			: new Outcome(fresh.status(), recorded.get().verdict(), recorded.get().confidence(), 0, null);
	}

	/**
	 * O que a peça é agora. A decisão é sempre a da peça: página que estava SAFE no ledger e voltou
	 * barrada passa pela barreira, e quem julga é o mesmo modelo da imagem — só a imagem, porque o
	 * PDF inteiro nunca vai ao classificador, só as folhas desenhadas dele.
	 */
	private Outcome checkPiece(String pieceId, String mediaId) {
		Optional<MediaAsset> asset = assets.findById(pieceId);
		if (asset.isEmpty()) {
			return new Outcome("UNKNOWN_MEDIA", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}
		String ownerId = asset.get().ownerId().value();
		if (asset.get().mimeType() == null || !asset.get().mimeType().toLowerCase(java.util.Locale.ROOT)
				.startsWith("image/")) {
			record(mediaId, pieceId, ownerId, MediaSafetyVerdict.UNSURE, "LOW", 0,
				"tipo de mídia fora da classificação de imagem");
			return new Outcome("UNSUPPORTED_MEDIA", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}

		List<byte[]> images = mediaReader.imagesOf("MEDIA", pieceId, 1);
		Optional<MediaSafetyAnalyzer.Verdict> verdict = images.isEmpty()
			? Optional.empty()
			: analyzer.analyze(images.get(0));
		if (verdict.isEmpty()) {
			// Sem palpite (modelo fora do ar, imagem ilegível): registra como UNSURE para não
			// repetir o download a cada rodada e deixa para a revisão humana.
			record(mediaId, pieceId, ownerId, MediaSafetyVerdict.UNSURE, "LOW", 0, "sem palpite automatico");
			return new Outcome("NO_VERDICT", MediaSafetyVerdict.UNSURE, 0, 0, null);
		}

		MediaSafetyAnalyzer.Verdict decision = verdict.get();
		record(mediaId, pieceId, ownerId, decision.verdict(), decision.severity(), decision.confidence(),
			decision.reason());
		LOG.info("media_safety mediaId={} peca={} owner={} veredito={} confianca={} gravidade={}", mediaId, pieceId,
			ownerId, decision.verdict(), decision.confidence(), decision.severity());

		if (!MediaSafetyAnalyzer.canActAlone(decision)) {
			return new Outcome("RECORDED", decision.verdict(), decision.confidence(), 0, null);
		}
		return enforce(mediaId, ownerId, decision);
	}

	/** Veredito de uma peça do documento: do ledger, ou novo; uma violação confirmada ainda no ar passa pela barreira. */
	private Outcome outcomeOf(PageRef page, String mediaId) {
		MediaSafetyCheck previous = checks.findById(page.mediaId()).orElse(null);
		if (previous == null) {
			return checkPiece(page.mediaId(), mediaId);
		}
		Outcome recorded = new Outcome("ALREADY_CHECKED", previous.verdict(), previous.confidence(), 0, null);
		if (!previous.verdict().isViolation()) {
			return recorded;
		}
		Optional<MediaAsset> asset = assets.findById(page.mediaId());
		// Pedido de remoção não é remoção: a folha que ainda espera a limpeza continua no ar, e é ela
		// que denuncia o documento. Só a folha efetivamente apagada fica fora da barreira.
		if (asset.isEmpty() || asset.get().deleteStatus() == MediaDeleteStatus.OBJECT_DELETED) {
			return recorded;
		}
		Outcome enforced = enforce(mediaId, asset.get().ownerId().value(),
			new MediaSafetyAnalyzer.Verdict(previous.verdict(), previous.severity(), previous.confidence(),
				previous.reason()));
		// Barreira que não agiu devolve o que o ledger já dizia: "nada mudou", não um takedown novo.
		return "ENFORCED".equals(enforced.status()) ? enforced : recorded;
	}

	private static int severityOf(Outcome outcome) {
		// O veredito mais grave responde pelo documento; UNSURE fica à frente de SAFE.
		return switch (outcome.verdict()) {
			case SEXUAL_MINOR -> 3;
			case EXPLICIT -> 2;
			case UNSURE -> 1;
			case SAFE -> 0;
		};
	}

	/**
	 * Documento sem página para olhar. O registro guarda o fato sem veredito: a ausência de SAFE é o
	 * que impede a liberação, e a varredura não volta a tentar o documento a cada rodada.
	 */
	private void recordUnseen(String mediaId) {
		String ownerId = ledger.ownerOf(mediaId);
		if (ownerId == null) {
			return;
		}
		record(mediaId, mediaId, ownerId, MediaSafetyVerdict.UNSURE, "LOW", 0,
			"documento sem pagina para classificar");
	}

	/**
	 * Tira a mídia do ar em todos os posts que a usam e bane a conta.
	 *
	 * <p>A remoção acontece ANTES da sanção: mesmo que a sanção falhe (usuário já banido, perfil
	 * sumiu), a imagem não pode continuar na timeline.</p>
	 */
	private Outcome enforce(String mediaId, String ownerId, MediaSafetyAnalyzer.Verdict decision) {
		// Primeiro a barreira: o veredito passa a contar como retirada, e a mídia para de ser
		// liberável (a peça sai do portfólio e da publicação) mesmo que o takedown abaixo falhe.
		// Peça de PDF leva o documento junto: folha explícita contamina o arquivo inteiro.
		retirement.retire(mediaId);
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

		// A mesma mídia pode estar na Colagem (foto publicada ou comentário fotográfico): sai de
		// lá também, senão o takedown deixa a imagem no ar num feed paralelo.
		try {
			removed += colagem.removeByMedia(mediaId, reason);
		}
		catch (RuntimeException failure) {
			LOG.warn("media_safety_colagem_remove_failed mediaId={} motivo={}", mediaId,
				failure.getClass().getSimpleName());
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

	/** Grava o resultado da peça: uma linha por peça, na qual a revisão humana se apoia depois. */
	private void record(String mediaId, String pieceId, String ownerId, MediaSafetyVerdict verdict, String severity,
			double confidence, String reason) {
		try {
			checks.save(MediaSafetyCheck.of(pieceId, ownerId, verdict, severity, confidence, reason,
				analyzer.modelName(), clock.instant()));
		}
		catch (RuntimeException failure) {
			// Ledger indisponível não pode impedir a barreira de agir.
			LOG.warn("media_safety_ledger_failed mediaId={} motivo={}", pieceId, failure.getClass().getSimpleName());
		}
	}

	/**
	 * O que a varredura ainda tem de olhar: imagens recentes sem veredito e páginas de PDF que nunca
	 * gravaram resultado (o render caiu no meio, por exemplo).
	 *
	 * <p>A folha é pedida pelo id dela e só se ainda existe, não saiu, e o documento está no ar: o
	 * check de uma página não é o check do documento, e página retirada não volta a ocupar a varredura.</p>
	 */
	public List<String> pending(Instant since, int limit) {
		int size = Math.max(1, limit);
		Set<String> candidates = new LinkedHashSet<>(checks.findUncheckedMediaIdsSince(since, size));
		// Os documentos são perguntados por dono: a lista do sistema ordenada por carimbo não alcança o
		// documento recém-desenhado, e uma mídia sem dono não existe no cadastro.
		for (String owner : checks.findUncheckedOwnersSince(since, size)) {
			if (candidates.size() >= size) {
				break;
			}
			for (String mediaId : retirement.documentsNeedingCheck(owner, since, size)) {
				candidates.add(mediaId);
			}
		}
		return List.copyOf(candidates);
	}
}

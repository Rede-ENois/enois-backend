package com.socialcoisa.rede.moderation.application;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.moderation.ai.LinkSafetyAnalyzer;
import com.socialcoisa.rede.moderation.application.port.out.LinkSafetyCheckRepository;
import com.socialcoisa.rede.moderation.application.port.out.LinkedPageReader;
import com.socialcoisa.rede.moderation.domain.LinkPolicy;
import com.socialcoisa.rede.moderation.domain.LinkSafetyCheck;
import com.socialcoisa.rede.moderation.domain.LinkSafetyVerdict;
import com.socialcoisa.rede.moderation.domain.PageSummary;
import com.socialcoisa.rede.moderation.domain.PostLinks;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Barreira automática do DESTINO dos links publicados.
 *
 * <p><b>Por que existe:</b> o texto de um post pode ser inocente e o link levar a pornografia — o
 * domínio pode ser uma plataforma conhecida, o título do post pode ser uma piada, e nada disso é
 * visível para a triagem de texto. Sem olhar o destino, esse post passa.</p>
 *
 * <p><b>Como decide:</b> primeiro o que dá para saber sem visitar (host de plataforma adulta é
 * conclusivo; palavra sexual no caminho é só suspeita). Depois o destino é lido — titulo e descrição
 * da própria página — e o modelo classifica. Só violação com confiança acima do piso aciona a
 * automação; o resto fica registrado no ledger e vai para revisão humana.</p>
 *
 * <p><b>O que a violação custa:</b> o post sai do ar, junto com qualquer outro post do mesmo autor
 * que aponte para o mesmo destino, e a conta é banida. O modelo nunca apaga nem bane — quem faz isso
 * é este serviço, e a conta sempre pode recorrer pelo fluxo normal de penalidade.</p>
 */
@Service
public class LinkSafetyService {

	private static final Logger LOG = LoggerFactory.getLogger(LinkSafetyService.class);

	/** Ator da trilha: é a automação, não uma pessoa. */
	static final String ACTOR = "automod:link";

	private final LinkSafetyCheckRepository checks;
	private final LinkedPageReader pageReader;
	private final LinkSafetyAnalyzer analyzer;
	private final PostRepository posts;
	private final ModerationRemovePostUseCase removePost;
	private final ModeratorService moderators;
	private final UserProfileRepository profiles;
	private final Clock clock;
	private final boolean enabled;
	private final int retryHours;

	public LinkSafetyService(LinkSafetyCheckRepository checks, LinkedPageReader pageReader,
			LinkSafetyAnalyzer analyzer, PostRepository posts, ModerationRemovePostUseCase removePost,
			ModeratorService moderators, UserProfileRepository profiles, Clock clock,
			@Value("${rede.moderation.links.enabled:true}") boolean enabled,
			@Value("${rede.moderation.links.retry-hours:24}") int retryHours) {
		this.checks = checks;
		this.pageReader = pageReader;
		this.analyzer = analyzer;
		this.posts = posts;
		this.removePost = removePost;
		this.moderators = moderators;
		this.profiles = profiles;
		this.clock = clock;
		this.enabled = enabled;
		this.retryHours = Math.max(1, retryHours);
	}

	public boolean enabled() {
		return enabled;
	}

	public record Outcome(String status, LinkSafetyVerdict verdict, double confidence, int removedPosts,
			String sanction) {
	}

	/**
	 * Checa os links de UM post. Idempotente por destino: URL já julgada não é visitada de novo, e
	 * destino que já foi barrado derruba na hora qualquer novo post que aponte para ele.
	 */
	public Outcome check(String postId) {
		if (!enabled) {
			return new Outcome("DISABLED", LinkSafetyVerdict.UNSURE, 0, 0, null);
		}
		Optional<Post> encontrado = posts.findById(new com.socialcoisa.rede.feed.domain.PostId(postId));
		if (encontrado.isEmpty()) {
			return new Outcome("UNKNOWN_POST", LinkSafetyVerdict.UNSURE, 0, 0, null);
		}
		Post post = encontrado.get();
		List<URI> links = PostLinks.extract(texto(post));
		if (links.isEmpty()) {
			return new Outcome("NO_LINKS", LinkSafetyVerdict.UNSURE, 0, 0, null);
		}

		boolean algumJulgado = false;
		for (URI link : links) {
			Optional<LinkSafetyAnalyzer.Verdict> decisao = judge(link, post.authorId().value());
			if (decisao.isEmpty()) {
				continue;
			}
			algumJulgado = true;
			LinkSafetyAnalyzer.Verdict veredito = decisao.get();
			if (veredito.verdict().isViolation() && LinkSafetyAnalyzer.canActAlone(veredito)) {
				return enforce(link, post, veredito);
			}
		}
		return new Outcome(algumJulgado ? "RECORDED" : "NO_VERDICT", LinkSafetyVerdict.UNSURE, 0, 0, null);
	}

	/**
	 * Veredito de um destino: ledger primeiro (não visita duas vezes), depois a política sem
	 * visita, depois a leitura da página. Nada aqui lança — link que não responde vira UNSURE.
	 */
	private Optional<LinkSafetyAnalyzer.Verdict> judge(URI link, String ownerId) {
		Optional<LinkSafetyCheck> anterior = checks.findByUrl(link.toString());
		if (anterior.isPresent()) {
			LinkSafetyCheck registro = anterior.get();
			// Veredito definitivo vale para sempre; UNSURE é "não deu para ver agora" — volta a
			// tentar depois do descanso, senão um destino fora do ar ficaria sem julgamento eterno.
			boolean definitivo = registro.verdict() != LinkSafetyVerdict.UNSURE;
			boolean dentroDoDescanso = registro.checkedAt()
				.isAfter(clock.instant().minus(java.time.Duration.ofHours(retryHours)));
			if (definitivo || dentroDoDescanso) {
				return Optional.of(new LinkSafetyAnalyzer.Verdict(registro.verdict(), registro.severity(),
					registro.confidence(), registro.reason()));
			}
		}

		// Host de plataforma adulta é conclusivo e não precisa de visita nem de modelo.
		if (LinkPolicy.isKnownAdultHost(link)) {
			LinkSafetyAnalyzer.Verdict veredito = new LinkSafetyAnalyzer.Verdict(LinkSafetyVerdict.EXPLICIT, "HIGH",
				0.99, "destino de plataforma de conteudo adulto");
			record(link, ownerId, veredito);
			LOG.info("link_safety host_adulto url={}", link);
			return Optional.of(veredito);
		}

		Optional<PageSummary> pagina = pageReader.summaryOf(link);
		if (pagina.isEmpty() || !pagina.get().hasAnything()) {
			// Sem descrição não há o que julgar: registra para não visitar de novo a cada rodada.
			record(link, ownerId, new LinkSafetyAnalyzer.Verdict(LinkSafetyVerdict.UNSURE, "LOW", 0,
				"destino sem descricao legivel"));
			return Optional.empty();
		}
		Optional<LinkSafetyAnalyzer.Verdict> veredito = analyzer.analyze(pagina.get());
		if (veredito.isEmpty()) {
			record(link, ownerId, new LinkSafetyAnalyzer.Verdict(LinkSafetyVerdict.UNSURE, "LOW", 0,
				"sem palpite automatico"));
			return Optional.empty();
		}
		record(link, ownerId, veredito.get());
		LOG.info("link_safety url={} host={} veredito={} confianca={} suspeita_no_caminho={}", link, link.getHost(),
			veredito.get().verdict(), veredito.get().confidence(), LinkPolicy.hasSexualToken(link));
		return veredito;
	}

	/**
	 * Tira do ar todo post do autor que aponte para o destino e pune a conta.
	 *
	 * <p>A remoção vem antes da sanção: mesmo que a sanção falhe, o link não pode continuar ao
	 * alcance de quem lê.</p>
	 */
	private Outcome enforce(URI link, Post origem, LinkSafetyAnalyzer.Verdict veredito) {
		String authorId = origem.authorId().value();
		String motivo = "Automod de link: " + veredito.reason();

		// O post que disparou entra sempre; os outros do mesmo autor entram se apontarem para o
		// mesmo destino. Sem a primeira garantia, um limite futuro em `findAllByAuthor` deixaria o
		// próprio post acusado no ar.
		java.util.Set<String> alvos = new java.util.LinkedHashSet<>();
		alvos.add(origem.id().value());
		for (Post outro : posts.findAllByAuthor(new com.socialcoisa.rede.feed.domain.UserId(authorId))) {
			if (apontaPara(outro, link)) {
				alvos.add(outro.id().value());
			}
		}

		int removidos = 0;
		for (String postId : alvos) {
			try {
				removePost.removeAsModerator(ACTOR, postId, motivo);
				removidos++;
			}
			catch (RuntimeException falha) {
				LOG.warn("link_safety_remove_failed post={} motivo={}", postId, falha.getClass().getSimpleName());
			}
		}

		String sancao = "NONE";
		UserProfile autor = profiles.findById(authorId).orElse(null);
		if (autor != null) {
			try {
				// Destino sexual explícito não volta com advertência — a diretriz da casa para esse
				// conteúdo é expulsão, igual à da imagem. Golpe/phishing fica na restrição com recurso.
				SanctionType tipo = veredito.verdict() == LinkSafetyVerdict.EXPLICIT ? SanctionType.PERMABAN
						: SanctionType.RESTRICTION;
				var resultado = moderators.sanctionUser(ACTOR, autor.username(), tipo, motivo);
				sancao = resultado.status();
				if (tipo == SanctionType.PERMABAN && !"ALREADY_BANNED".equals(resultado.status())) {
					moderators.sanitizeUser(ACTOR, autor.username(), motivo);
				}
				LOG.warn("link_safety_sanction username={} url={} veredito={} posts={}", autor.username(), link,
					veredito.verdict(), removidos);
			}
			catch (RuntimeException falha) {
				sancao = "FAILED";
				LOG.error("link_safety_sanction_failed username={} url={} motivo={}", autor.username(), link,
					falha.getClass().getSimpleName());
			}
		}
		return new Outcome("ENFORCED", veredito.verdict(), veredito.confidence(), removidos, sancao);
	}

	private void record(URI link, String ownerId, LinkSafetyAnalyzer.Verdict veredito) {
		try {
			checks.save(LinkSafetyCheck.of(link.toString(), ownerId, veredito.verdict(), veredito.severity(),
				veredito.confidence(), veredito.reason(), analyzer.modelName(), clock.instant()));
		}
		catch (RuntimeException falha) {
			// Ledger indisponível não pode impedir a barreira de agir.
			LOG.warn("link_safety_ledger_failed url={} motivo={}", link, falha.getClass().getSimpleName());
		}
	}

	/** Texto do post que pode conter link: o corpo e, na mini página, o HTML já sanitizado. */
	private static String texto(Post post) {
		String corpo = post.content() == null ? "" : post.content();
		String pagina = post.pageHtml() == null ? "" : " " + post.pageHtml();
		return corpo + pagina;
	}

	private static boolean apontaPara(Post post, URI link) {
		for (URI outro : PostLinks.extract(texto(post))) {
			if (outro.equals(link)) {
				return true;
			}
		}
		return false;
	}

}
package com.socialcoisa.rede.linkpreview.application;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.blog.application.BlogService;
import com.socialcoisa.rede.blog.application.BlogView;
import com.socialcoisa.rede.communities.application.CommunityService;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.events.application.EventService;
import com.socialcoisa.rede.events.domain.Event;
import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.port.in.GetPostUseCase;
import com.socialcoisa.rede.games.application.GameService;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.jobs.application.JobService;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.linkpreview.domain.LinkPreview;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;

/**
 * Transforma um endereco do proprio Énóis na miniatura da pagina que ele abre.
 *
 * <p>Um link interno nao e buscado na internet: o resumo sai das MESMAS leituras que a pagina faz,
 * com as mesmas regras (post de comunidade privada continua invisivel para quem nao e membro,
 * conta banida nao aparece, vaga fechada nao vira cartao). Quem le nao ve conteudo que a pagina
 * dele nao mostraria.</p>
 *
 * <p>O endereco chega como texto do post, entao pode ser a URL completa do site ou so o caminho.
 * O caminho passa a ser a chave de cache; um endereco que nao e de nenhuma pagina conhecida
 * devolve vazio e o link segue para a leitura externa (ou fica sem cartao).</p>
 */
@Component
public class InternalPageResolver {

	private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?"
			+ "[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");

	private static final int EXCERPT = 220;

	private final GetPostUseCase posts;
	private final UserProfileRepository profiles;
	private final BlogService blogs;
	private final JobService jobs;
	private final CommunityService communities;
	private final EventService events;
	private final GameService games;

	public InternalPageResolver(GetPostUseCase posts, UserProfileRepository profiles, BlogService blogs, JobService jobs,
			CommunityService communities, EventService events, GameService games) {
		this.posts = posts;
		this.profiles = profiles;
		this.blogs = blogs;
		this.jobs = jobs;
		this.communities = communities;
		this.events = events;
		this.games = games;
	}

	/**
	 * A miniatura da pagina interna que o caminho abre.
	 *
	 * <p>{@code viewerId} e quem esta lendo: a leitura do post usa as mesmas regras da pagina (post
	 * de canal privado so existe para membro). Sem sessao, o visitante e anonimo.</p>
	 */
	@Transactional(readOnly = true)
	public Optional<LinkPreview> resolve(String pagePath, String viewerId) {
		List<String> segments = segmentsOf(pagePath);
		if (segments.isEmpty()) {
			return Optional.empty();
		}
		try {
			return switch (segments.get(0)) {
				case "post" -> segments.size() == 2 ? post(pagePath, segments.get(1), viewerId) : Optional.empty();
				case "perfil" -> segments.size() == 2 ? profile(pagePath, segments.get(1)) : Optional.empty();
				case "blog" -> blog(pagePath, segments, viewerId);
				case "vagas" -> segments.size() == 2 ? job(pagePath, segments.get(1)) : Optional.empty();
				case "comunidades" -> segments.size() == 2 ? community(pagePath, segments.get(1)) : Optional.empty();
				case "games" -> game(pagePath, segments);
				case "grupos" -> Optional.empty();
				case "eventos" -> Optional.empty();
				default -> Optional.empty();
			};
		}
		catch (RuntimeException naoEncontrado) {
			// A pagina nao existe mais (ou nao e visivel para quem le): link sem cartao, nunca erro
			// no post de quem so escreveu um endereco.
			return Optional.empty();
		}
	}

	/** O caminho de uma pagina interna, quando o endereco aponta para este site. */
	List<String> segmentsOf(String pagePath) {
		if (pagePath == null || pagePath.isBlank()) {
			return List.of();
		}
		String path = pagePath.trim();
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		int hash = path.indexOf('#');
		if (hash >= 0) {
			path = path.substring(0, hash);
		}
		if (path.startsWith("/")) {
			path = path.substring(1);
		}
		if (path.isEmpty()) {
			return List.of();
		}
		return List.of(path.split("/")).stream()
			.filter(segmento -> !segmento.isBlank())
			.map(segmento -> segmento.toLowerCase(Locale.ROOT))
			// O caminho chega com escape de URL; o identificador publicado e o decode.
			.map(segmento -> java.net.URLDecoder.decode(segmento, java.nio.charset.StandardCharsets.UTF_8))
			.toList();
	}

	private Optional<LinkPreview> post(String path, String postId, String viewerId) {
		if (!UUID.matcher(postId).matches()) {
			return Optional.empty();
		}
		PostView post = posts.getPost(postId,
			viewerId == null ? null : new com.socialcoisa.rede.feed.domain.UserId(viewerId));
		String autor = post.authorName() == null ? post.authorUsername() : post.authorName();
		String texto = post.content() == null || post.content().isBlank()
			? (post.type() == com.socialcoisa.rede.feed.domain.PostType.PAGE ? "Página publicada" : null)
			: post.content();
		return Optional.of(LinkPreview.internal(path, autor == null ? "Post" : autor + " no Énóis",
			excerpt(texto), firstMedia(post.mediaIds()), "Post"));
	}

	private Optional<LinkPreview> profile(String path, String username) {
		UserProfile profile = profiles.findByUsername(username).orElse(null);
		if (profile == null) {
			return Optional.empty();
		}
		String description = profile.headline() != null && !profile.headline().isBlank()
			? profile.headline()
			: null;
		return Optional.of(LinkPreview.internal(path, profile.name() == null ? "@" + username : profile.name(),
			trimmed(description), profile.banner() != null ? profile.banner() : profile.avatar(), "Perfil"));
	}

	private Optional<LinkPreview> blog(String path, List<String> segments, String viewerId) {
		if (segments.size() < 2) {
			return Optional.empty();
		}
		Optional<BlogView> blog = blogs.blogOf(segments.get(1), viewerId);
		if (blog.isEmpty()) {
			return Optional.empty();
		}
		BlogView view = blog.get();
		if (segments.size() == 2) {
			return Optional.of(LinkPreview.internal(path, titleOf(view), trimmed(view.description()),
				view.authorAvatar(), "Blog"));
		}
		// /blog/<username>/<postId>: o post do blog mora na lista do proprio blog.
		String postId = segments.get(2);
		return view.items().stream()
			.filter(item -> item.id().equals(postId))
			.findFirst()
			.map(item -> LinkPreview.internal(path, item.title(), trimmed(item.body()), view.authorAvatar(), "Blog"));
	}

	private Optional<LinkPreview> job(String path, String jobId) {
		Job job = jobs.getById(jobId);
		return Optional.of(LinkPreview.internal(path, job.title(), excerpt(job.description()), job.logo(), "Vaga"));
	}

	private Optional<LinkPreview> community(String path, String communityId) {
		Community community = communities.getById(communityId);
		return Optional.of(LinkPreview.internal(path, community.name(), trimmed(community.description()),
			community.cover() != null ? community.cover() : community.avatar(), "Comunidade"));
	}

	private Optional<LinkPreview> game(String path, List<String> segments) {
		if (segments.size() == 2) {
			UserProfile creator = profiles.findByUsername(segments.get(1)).orElse(null);
			if (creator == null) {
				return Optional.empty();
			}
			return Optional.of(LinkPreview.internal(path, creator.name(), trimmed(creator.headline()),
				creator.banner() != null ? creator.banner() : creator.avatar(), "Jogos"));
		}
		if (segments.size() == 3) {
			Game game = games.byUserAndSlug(segments.get(1), segments.get(2));
			return Optional.of(LinkPreview.internal(path, game.title(), trimmed(game.description()), game.cover(),
				"Jogo"));
		}
		return Optional.empty();
	}

	private static String titleOf(BlogView view) {
		if (view.title() != null && !view.title().isBlank()) {
			return view.title();
		}
		return view.authorName() + " · Blog";
	}

	private static String firstMedia(List<String> mediaIds) {
		return mediaIds == null || mediaIds.isEmpty() ? null : "/api/media/" + mediaIds.get(0);
	}

	private static String trimmed(String value) {
		if (value == null) {
			return null;
		}
		String limpo = value.replaceAll("\\s+", " ").trim();
		return limpo.isEmpty() ? null : limpo;
	}

	/**
	 * O comeco do texto, sem markup. O corpo do post e markdown leve, entao o cartao mostra o texto
	 * corrido: tags e marcadores no cartao seriam ruido. A limpeza e a mesma da previa do blog —
	 * uma regra so para "texto de post sem formatacao" no projeto.
	 */
	static String excerpt(String value) {
		if (value == null) {
			return null;
		}
		String text = com.socialcoisa.rede.blog.application.BlogNewsletterService.previewOf(value);
		if (text.isEmpty()) {
			return null;
		}
		return text.length() <= EXCERPT ? text : text.substring(0, EXCERPT) + "…";
	}
}

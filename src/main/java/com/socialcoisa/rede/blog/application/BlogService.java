package com.socialcoisa.rede.blog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.blog.application.port.out.BlogPostRepository;
import com.socialcoisa.rede.blog.application.port.out.BlogRepository;
import com.socialcoisa.rede.blog.application.port.out.BlogSubscriptionRepository;
import com.socialcoisa.rede.blog.domain.Blog;
import com.socialcoisa.rede.blog.domain.BlogPost;
import com.socialcoisa.rede.blog.domain.BlogPostFormat;
import com.socialcoisa.rede.blog.domain.BlogSubscription;
import com.socialcoisa.rede.feed.application.DeletePostCommand;
import com.socialcoisa.rede.feed.application.PublishPostCommand;
import com.socialcoisa.rede.feed.application.port.in.DeletePostUseCase;
import com.socialcoisa.rede.feed.application.port.in.PublishPostUseCase;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O cantinho de cada pessoa: um blog com endereco proprio (`/blog/{username}`), posts que NAO entram
 * no feed global e inscricao para acompanhar.
 *
 * Quando um post sai, ele vira tambem um post-ponteiro no feed (tipo BLOG, com a tag "blog") — esse
 * ponteiro so aparece no "Para voce" de quem se inscreveu no blog (nunca no "Tudo") e avisa os
 * inscritos por notificacao. O id do post do blog e o MESMO id do ponteiro, entao o card do feed
 * linka direto para o post sem guardar nada a mais.
 */
@Service
public class BlogService {

	private static final int MAX_BLOG_TITLE = 160;
	private static final int MAX_DESCRIPTION = 500;
	private static final int MAX_POST_TITLE = 200;
	/** Teto do corpo em markdown: o mesmo do HTML — um limite so, para todo mundo. */
	private static final int MAX_MARKDOWN = 20_000;
	private static final int POSTS_PAGE = 50;

	private final BlogRepository blogs;
	private final BlogPostRepository posts;
	private final BlogSubscriptionRepository subscriptions;
	private final PublishPostUseCase publishPost;
	private final DeletePostUseCase deletePost;
	private final NotificationService notifications;
	private final UserProfileRepository profiles;
	private final ApplicationEventPublisher events;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;
	private final com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText;
	private final Clock clock;

	public BlogService(BlogRepository blogs, BlogPostRepository posts, BlogSubscriptionRepository subscriptions,
			PublishPostUseCase publishPost, DeletePostUseCase deletePost, NotificationService notifications,
			UserProfileRepository profiles, ApplicationEventPublisher events,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate,
			com.socialcoisa.rede.shared.security.SuspiciousText suspiciousText, Clock clock) {
		this.blogs = blogs;
		this.posts = posts;
		this.subscriptions = subscriptions;
		this.publishPost = publishPost;
		this.deletePost = deletePost;
		this.notifications = notifications;
		this.profiles = profiles;
		this.events = events;
		this.sanctionGate = sanctionGate;
		this.suspiciousText = suspiciousText;
		this.clock = clock;
	}

	/** Vitrine: os blogs criados, mais recentes primeiro. */
	@Transactional(readOnly = true)
	public List<BlogSummaryView> directory(int limit) {
		List<Blog> page = blogs.findAll(limit);
		// Bans, authors and post counts of the page in one query each, instead of one per blog.
		Set<String> banned = sanctionGate.bannedAmong(page.stream().map(Blog::userId).collect(Collectors.toSet()));
		List<Blog> listed = page.stream()
			// Blog de conta banida sai da vitrine (o perfil já dá 404).
			.filter(blog -> !banned.contains(blog.userId()))
			.filter(blog -> !suspiciousText.matches(blog.title(), blog.slug()))
			.toList();
		Map<String, UserProfile> authors = profiles.findAllByIds(listed.stream().map(Blog::userId).toList())
			.stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		Map<String, Integer> postCounts = posts.countByBlogs(listed.stream().map(Blog::id).toList());
		return listed.stream()
			.map(blog -> BlogSummaryView.from(blog, authors.get(blog.userId()), postCounts.getOrDefault(blog.id(), 0)))
			.toList();
	}

	/** A pagina publica do blog: posts, dados do autor e o estado de quem esta lendo. */
	@Transactional(readOnly = true)
	public Optional<BlogView> blogOf(String slug, String viewerId) {
		return blogs.findBySlug(slug)
			.filter(blog -> !sanctionGate.banned(blog.userId()))
			.filter(blog -> !suspiciousText.matches(blog.title(), blog.slug()))
			.map(blog -> view(blog, viewerId, POSTS_PAGE));
	}

	/** O meu blog (para o editor). Vazio quando ainda nao existe — a tela oferece criar. */
	@Transactional(readOnly = true)
	public Optional<BlogView> mine(String userId) {
		return blogs.findByUserId(userId).map(blog -> view(blog, userId, POSTS_PAGE));
	}

	/**
	 * Cria ou edita o meu blog. O endereco acompanha o username: se a pessoa trocar de username, o
	 * slug e re-sincronizado aqui (o link antigo deixa de existir, como no perfil).
	 */
	@Transactional
	public BlogView saveMine(String userId, String title, String description, String themeHtml) {
		UserProfile profile = profiles.findById(userId)
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil nao encontrado."));
		String cleanTitle = StringUtils.hasText(title) ? title.trim() : null;
		if (cleanTitle != null) {
			check("titulo", cleanTitle, MAX_BLOG_TITLE);
		}
		String cleanDescription = description == null ? null : description.trim();
		if (cleanDescription != null) {
			check("descricao", cleanDescription, MAX_DESCRIPTION);
		}
		// Vazio LIMPA o tema (volta ao cabecalho padrao); ausente mantem o que ja estava.
		String cleanTheme = themeHtml == null ? null
			: (StringUtils.hasText(themeHtml) ? SafeHtml.requireBlogHtml(themeHtml) : "");

		Instant now = Instant.now(clock);
		Blog blog = blogs.findByUserId(userId).orElseGet(() -> blogs.save(Blog.create(
			"blog_" + UUID.randomUUID().toString().replace("-", ""), userId, profile.username(),
			cleanTitle != null ? cleanTitle : profile.username(), cleanDescription, now)));
		blog.update(cleanTitle, cleanDescription, cleanTheme, now);
		Blog saved = blogs.save(blog);
		return view(saved, userId, POSTS_PAGE);
	}

	/** Publica um post no blog avisando quem segue (comportamento de sempre). */
	@Transactional
	public BlogPostView publish(String userId, String title, String body, BlogPostFormat format) {
		return publish(userId, title, body, format, true);
	}

	/**
	 * Publica um post no blog: ponteiro no feed (tag "blog"), notificacao in-app e E-MAIL para os
	 * assinantes — o toggle `notify` desliga os dois avisos (e so no PRIMEIRO publish; editar
	 * nunca reenvia). O e-mail roda depois do commit (ver BlogNewsletterService).
	 */
	@Transactional
	public BlogPostView publish(String userId, String title, String body, BlogPostFormat format, boolean notify) {
		String cleanTitle = requireText(title, MAX_POST_TITLE, "titulo", "Post precisa de um titulo.");
		String cleanBody = cleanBody(body, format);
		Blog blog = ensureBlog(userId);

		// O ponteiro nasce primeiro: o id dele e o id do post do blog (e o que o card do feed linka).
		var pointer = publishPost.publish(new PublishPostCommand(new UserId(userId), PostType.BLOG, cleanTitle, null,
			null, List.of("blog"), List.of(), null, List.of()));
		String postId = pointer.id();
		BlogPost saved = posts.save(BlogPost.publish(postId, blog.id(), userId, cleanTitle, cleanBody, format,
			Instant.now(clock)));
		if (notify) {
			notifySubscribers(blog, saved);
			// O fan-out de e-mail escuta DEPOIS do commit: nada sai se a transacao reverter.
			events.publishEvent(new BlogPostPublishedEvent(blog.id(), blog.slug(), blog.title(), blog.userId(),
				saved.id(), cleanTitle, cleanBody));
		}
		return BlogPostView.from(saved);
	}

	@Transactional
	public BlogPostView edit(String userId, String postId, String title, String body, BlogPostFormat format) {
		BlogPost post = ownedPost(userId, postId);
		String cleanTitle = requireText(title, MAX_POST_TITLE, "titulo", "Post precisa de um titulo.");
		post.edit(cleanTitle, cleanBody(body, format), format, Instant.now(clock));
		return BlogPostView.from(posts.save(post));
	}

	@Transactional
	public void delete(String userId, String postId) {
		BlogPost post = ownedPost(userId, postId);
		posts.delete(post.id());
		// O ponteiro sai do feed junto (soft delete no feed, como qualquer post apagado pelo autor).
		deletePost.delete(new DeletePostCommand(new UserId(userId), new PostId(post.id()), "post do blog apagado"));
	}

	/** Inscrever-se: e o que faz as novidades do blog aparecerem no "Para voce" de quem segue. */
	@Transactional
	public boolean subscribe(String userId, String slug) {
		Blog blog = blogBySlug(slug);
		if (subscriptions.exists(blog.id(), userId)) {
			return true;
		}
		subscriptions.save(BlogSubscription.create("bsub_" + UUID.randomUUID().toString().replace("-", ""), blog.id(),
			userId, Instant.now(clock)));
		return true;
	}

	@Transactional
	public boolean unsubscribe(String userId, String slug) {
		Blog blog = blogBySlug(slug);
		subscriptions.delete(blog.id(), userId);
		return false;
	}

	private Blog blogBySlug(String slug) {
		return blogs.findBySlug(slug)
			.orElseThrow(() -> new ApiException(404, "BLOG_NOT_FOUND", "Blog nao encontrado."));
	}

	/** Publicar sem ter criado o blog cria um com o nome de usuario — menos um passo para quem so quer escrever. */
	private Blog ensureBlog(String userId) {
		return blogs.findByUserId(userId).orElseGet(() -> {
			UserProfile profile = profiles.findById(userId)
				.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil nao encontrado."));
			Instant now = Instant.now(clock);
			return blogs.save(Blog.create("blog_" + UUID.randomUUID().toString().replace("-", ""), userId,
				profile.username(), profile.username(), null, now));
		});
	}

	private BlogPost ownedPost(String userId, String postId) {
		BlogPost post = posts.findById(postId)
			.orElseThrow(() -> new ApiException(404, "BLOG_POST_NOT_FOUND", "Post do blog nao encontrado."));
		if (!post.userId().equals(userId)) {
			throw new ApiException(403, "NOT_BLOG_OWNER", "Este post nao e seu.");
		}
		return post;
	}

	private void notifySubscribers(Blog blog, BlogPost post) {
		String author = profiles.findById(blog.userId()).map(UserProfile::name).orElse(blog.slug());
		// O endereço da página do blog é o USERNAME ATUAL, não o slug gravado: quem trocou de
		// username sem editar o blog ficava com notificação apontando para blog inexistente
		// (relato de suporte: "clico em ler e nada acontece").
		String href = "/blog/"
			+ profiles.findById(blog.userId()).map(UserProfile::username).orElse(blog.slug()) + "/"
			+ post.id();
		for (String subscriberId : subscriptions.findSubscriberIds(blog.id())) {
			if (subscriberId.equals(blog.userId())) {
				continue; // o autor nao precisa ser avisado do proprio post
			}
			notifications.create(subscriberId, "NEW_BLOG_POST", blog.userId(), author + " publicou no blog",
				post.title(), href, "Ler");
		}
	}

	private BlogView view(Blog blog, String viewerId, int limit) {
		List<BlogPostView> items = posts.findByBlog(blog.id(), null, limit).stream().map(BlogPostView::from).toList();
		boolean subscribed = viewerId != null && subscriptions.exists(blog.id(), viewerId);
		boolean owner = viewerId != null && viewerId.equals(blog.userId());
		return BlogView.from(blog, profiles.findById(blog.userId()).orElse(null), items.size(), subscribed, owner,
			items);
	}

	/** Corpo do post: HTML entra sanitizado; markdown entra como texto (o front formata) com a defesa de sempre. */
	private String cleanBody(String body, BlogPostFormat format) {
		if (!StringUtils.hasText(body)) {
			throw new ApiException(400, "INVALID_BLOG_BODY", "Post precisa de conteudo.");
		}
		if (format == BlogPostFormat.HTML) {
			return SafeHtml.requireBlogHtml(body);
		}
		String clean = PlainText.defuse(body.trim());
		check("conteudo", clean, MAX_MARKDOWN);
		return clean;
	}

	private String requireText(String value, int max, String field, String message) {
		if (!StringUtils.hasText(value)) {
			throw new ApiException(400, "INVALID_BLOG_TITLE", message);
		}
		String clean = value.trim();
		check(field, clean, max);
		return clean;
	}

	private void check(String field, String value, int max) {
		if (value != null && value.length() > max) {
			throw new ApiException(400, "BLOG_FIELD_TOO_LONG",
				"O campo " + field + " aceita no maximo " + max + " caracteres.");
		}
	}
}

package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.feed.application.port.in.GetFeedUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetPostUseCase;
import com.socialcoisa.rede.feed.application.port.in.GetProfilePostsUseCase;
import com.socialcoisa.rede.feed.application.port.out.CommentRepository;
import com.socialcoisa.rede.feed.application.port.out.ConnectionGraphRepository;
import com.socialcoisa.rede.feed.application.port.out.FeedCacheRepository;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.application.port.out.ArticleRepository;
import com.socialcoisa.rede.feed.application.port.out.PostRepository;
import com.socialcoisa.rede.feed.application.port.out.SubscribedBlogAuthorsReader;
import com.socialcoisa.rede.feed.domain.FeedItem;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.feed.application.port.out.PostCommunityReader;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.CursorCodec;

@Service
public class GetFeedService implements GetFeedUseCase, GetProfilePostsUseCase, GetPostUseCase {

	private final PostRepository postRepository;
	private final ArticleRepository articleRepository;
	private final FeedCacheRepository feedCacheRepository;
	private final ConnectionGraphRepository connectionGraphRepository;
	private final UserProfileRepository userProfileRepository;
	private final PostInteractionRepository postInteractionRepository;
	private final CommentRepository commentRepository;
	private final CursorCodec cursorCodec;
	private final BlockedUsersReader blockedUsersReader;
	private final PostCommunityReader postCommunityReader;
	private final SubscribedBlogAuthorsReader subscribedBlogAuthors;
	private static final Instant DEFAULT_BEFORE = Instant.parse("9999-12-31T23:59:59Z");

	public GetFeedService(PostRepository postRepository, ArticleRepository articleRepository,
			FeedCacheRepository feedCacheRepository,
			ConnectionGraphRepository connectionGraphRepository, UserProfileRepository userProfileRepository,
			PostInteractionRepository postInteractionRepository, CommentRepository commentRepository,
			CursorCodec cursorCodec, BlockedUsersReader blockedUsersReader, PostCommunityReader postCommunityReader,
			SubscribedBlogAuthorsReader subscribedBlogAuthors) {
		this.postRepository = postRepository;
		this.articleRepository = articleRepository;
		this.feedCacheRepository = feedCacheRepository;
		this.connectionGraphRepository = connectionGraphRepository;
		this.userProfileRepository = userProfileRepository;
		this.postInteractionRepository = postInteractionRepository;
		this.commentRepository = commentRepository;
		this.cursorCodec = cursorCodec;
		this.blockedUsersReader = blockedUsersReader;
		this.postCommunityReader = postCommunityReader;
		this.subscribedBlogAuthors = subscribedBlogAuthors;
	}

	@Override
	@Transactional(readOnly = true)
	public FeedPage getFeed(GetFeedQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		FeedTab tab = query.tab() != null ? query.tab() : FeedTab.FOR_YOU;
		if (tab == FeedTab.FOR_YOU) {
			return forYou(query, before, limit);
		}
		List<Post> posts = switch (tab) {
			case FOR_YOU, FOLLOWING -> {
				Set<UserId> authors = new LinkedHashSet<>();
				authors.add(query.userId());
				authors.addAll(connectionGraphRepository.findFriends(query.userId()));
				yield postRepository.findActiveByAuthors(authors, before, limit);
			}
			case ALL -> postRepository.findActiveAll(before, limit);
			case DISCUSSIONS, RECENT -> {
				List<String> postIds = commentRepository.findPostIdsByAuthor(query.userId().value());
				yield postRepository.findByIds(postIds.stream().map(PostId::new).toList()).stream()
					.filter(Post::isActive)
					.filter(p -> p.createdAt().isBefore(before))
					.limit(limit)
					.toList();
			}
			case PROJECTS -> postRepository.findActiveByType(PostType.PROJECT, before, limit);
		};
		return page(enrich(posts, query.userId()), posts, limit);
	}

	@Override
	@Transactional(readOnly = true)
	public FeedPage getProfilePosts(GetProfilePostsQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		// O path aceita username ou id; o conteudo guarda author_id, entao resolvemos o username.
		String authorId = userProfileRepository.findByUsername(query.username())
			.map(com.socialcoisa.rede.profile.domain.UserProfile::id)
			.orElse(query.username());
		List<Post> posts = postRepository.findActiveByAuthor(new UserId(authorId), before, limit);
		return page(enrich(posts, query.viewerId()), posts, limit);
	}

	@Override
	@Transactional(readOnly = true)
	public PostView getPost(String postId, UserId viewerId) {
		Post post = postRepository.findById(new PostId(postId))
			.filter(Post::isActive)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		// Link direto (notificacao antiga, por exemplo) tambem respeita o bloqueio.
		if (viewerId != null && blockedUsersReader.hiddenIdsFor(viewerId.value()).contains(post.authorId().value())) {
			throw new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado.");
		}
		List<PostView> enriched = enrich(List.of(post), viewerId);
		// Unico lugar que devolve o corpo da pagina e o texto do artigo (ver PostView.from): e a
		// leitura de um post so — a listagem nao carrega nem um nem outro.
		return (enriched.isEmpty() ? PostView.from(post) : enriched.get(0))
			.withPageHtml(post.pageHtml())
			.withArticleBody(articleRepository.findBody(post.id()).orElse(null));
	}

	@Transactional(readOnly = true)
	public FeedPage getSavedFeed(UserId userId) {
		List<String> savedIds = postInteractionRepository.findSavedPostIdsByUserId(userId);
		if (savedIds.isEmpty()) return new FeedPage(List.of(), null);
		List<PostId> postIds = savedIds.stream().map(PostId::new).toList();
		List<Post> posts = postRepository.findByIds(postIds).stream()
			.filter(Post::isActive).toList();
		List<PostView> enriched = enrich(posts, userId);
		List<PostView> ordered = savedIds.stream()
			.map(id -> enriched.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null))
			.filter(java.util.Objects::nonNull)
			.toList();
		return new FeedPage(ordered, null);
	}

	@Transactional(readOnly = true)
	public FeedPage getCommunityFeed(String communityId, Instant before, int limit, UserId currentUserId) {
		limit = normalizeLimit(limit);
		before = before == null ? DEFAULT_BEFORE : before;
		List<Post> posts = postRepository.findActiveByCommunity(communityId, before, limit);
		return page(enrich(posts, currentUserId), posts, limit);
	}

	private List<PostView> enrich(List<Post> posts, UserId currentUserId) {
		if (posts.isEmpty()) {
			return List.of();
		}
		Set<String> hidden = blockedUsersReader.hiddenIdsFor(currentUserId != null ? currentUserId.value() : null);
		List<Post> visible = hidden.isEmpty() ? posts
			: posts.stream().filter(post -> !hidden.contains(post.authorId().value())).toList();
		if (visible.isEmpty()) {
			return List.of();
		}
		Set<String> authorIds = visible.stream()
			.map(p -> p.authorId().value())
			.collect(Collectors.toSet());
		Map<String, UserProfile> profiles = userProfileRepository.findAllByIds(authorIds).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		// Comunidade dos posts da pagina: nome para o "publicou em ..." e se o leitor ja participa
		// (a consulta e em lote, uma para a pagina inteira).
		java.util.Set<String> communityIds = visible.stream()
			.map(Post::communityId)
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toSet());
		Map<String, PostCommunityReader.CommunityRef> communities = postCommunityReader
			.byIds(communityIds, currentUserId == null ? null : currentUserId.value());

		List<PostId> postIds = visible.stream().map(Post::id).toList();
		// Sem sessao (link compartilhado aberto por quem recebeu) nao ha reacao do visitante para
		// buscar: a leitura publica nao pode depender de um usuario logado.
		Map<PostId, ReactionType> currentUserReactions = currentUserId == null ? Map.of()
			: postInteractionRepository.findReactionsByUserAndPostIds(currentUserId, postIds);
		return visible.stream()
			.map(post -> {
				UserProfile profile = profiles.get(post.authorId().value());
				PostView view = PostView.from(post,
					profile != null ? profile.username() : null,
					profile != null ? profile.displayWithNickname() : post.authorId().value(),
					profile != null ? profile.avatar() : null,
					profile != null ? profile.allBadges() : List.of()).withAvatarFrame(profile != null ? profile.avatarFrame() : null);
				ReactionType reaction = currentUserReactions.get(post.id());
				PostView withReaction = reaction != null ? view.withCurrentUserReaction(reaction) : view;
				PostCommunityReader.CommunityRef community = post.communityId() == null ? null
					: communities.get(post.communityId());
				return community == null ? withReaction
					: withReaction.withCommunity(community.id(),
						new PostView.CommunityView(community.id(), community.name(), community.joined()));
			})
			.toList();
	}

	private List<PostView> filterByTab(List<PostView> posts, FeedTab tab) {
		if (tab == null || tab == FeedTab.FOR_YOU) {
			return posts;
		}
		return posts.stream()
			.filter(p -> matchesTab(p, tab))
			.toList();
	}

	private boolean matchesTab(PostView post, FeedTab tab) {
		return switch (tab) {
			case FOR_YOU, FOLLOWING, ALL -> true;
			case DISCUSSIONS, RECENT -> post.type() == PostType.QUESTION;
			case PROJECTS -> post.type() == PostType.PROJECT;
		};
	}

	private List<PostView> fromCache(UserId userId, Instant before, int limit) {
		List<FeedItem> items = feedCacheRepository.get(userId, before, limit);
		if (items.isEmpty()) {
			return List.of();
		}
		Map<String, FeedItem> orderedItems = new LinkedHashMap<>();
		for (FeedItem item : items) {
			orderedItems.put(item.postId().value(), item);
		}
		Map<PostId, Post> postsById = postRepository.findByIds(orderedItems.values().stream().map(FeedItem::postId).toList())
			.stream()
			.filter(Post::isActive)
			.collect(Collectors.toMap(Post::id, Function.identity()));
		List<Post> ordered = new ArrayList<>();
		for (FeedItem item : orderedItems.values()) {
			Post post = postsById.get(item.postId());
			if (post != null) {
				ordered.add(post);
			}
		}
		return enrich(ordered, userId).stream()
			.sorted(Comparator.comparing(PostView::createdAt).reversed())
			.toList();
	}

	/**
	 * O cursor sai da pagina que veio do banco, nao da lista exibida: quando um post e escondido por
	 * bloqueio a pagina fica menor, e sem isso o scroll infinito acharia que chegou ao fim.
	 */
	/**
	 * "Para voce": posts de quem a pessoa segue e os proprios, mas SO dentro das comunidades de que
	 * ela participa (ou fora de comunidade). Post de conexao numa comunidade estranha nao entra —
	 * feedback direto da comunidade: o feed virou vitrine de comunidade alheia. FOLLOWING continua
	 * mostrando tudo de quem a pessoa segue; a diferenca entre as abas e exatamente essa.
	 */
	private FeedPage forYou(GetFeedQuery query, Instant before, int limit) {
		Set<UserId> authors = new LinkedHashSet<>();
		authors.add(query.userId());
		authors.addAll(connectionGraphRepository.findFriends(query.userId()));
		// Blogs que a pessoa segue: a novidade deles aparece AQUI. E o unico caminho para um post de
		// blog no feed — a listagem global ("Tudo") exclui o tipo BLOG, entao ele nunca cai la.
		subscribedBlogAuthors.authorsFollowedBy(query.userId().value()).stream()
			.map(UserId::new)
			.forEach(authors::add);
		// Busca mais que o limite porque a filtragem por comunidade pode encolher a pagina.
		List<Post> fetched = postRepository.findActiveByAuthors(authors, before, limit * 3);
		Set<String> hiddenCommunities = communityIdsWithoutMembership(fetched, query.userId());
		List<Post> visible = fetched.stream()
			.filter(post -> post.communityId() == null || !hiddenCommunities.contains(post.communityId()))
			.limit(limit)
			.toList();
		// Cursor pela pagina EXIBIDA (nao pela bruta): repetir post ou pular pedaco seria pior do que
		// uma pagina mais curta. So ha "proxima pagina" quando a busca bruta veio saturada.
		String nextCursor = fetched.size() == limit * 3 && !visible.isEmpty()
			? cursorCodec.encode(visible.get(visible.size() - 1).createdAt()) : null;
		return new FeedPage(enrich(visible, query.userId()), nextCursor);
	}

	/** Comunidades da pagina em que o leitor NAO participa (uma consulta em lote, nao uma por post). */
	private Set<String> communityIdsWithoutMembership(List<Post> posts, UserId viewer) {
		Set<String> communityIds = posts.stream()
			.map(Post::communityId)
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toSet());
		if (communityIds.isEmpty()) {
			return Set.of();
		}
		return postCommunityReader.byIds(communityIds, viewer.value()).entrySet().stream()
			.filter(entry -> !entry.getValue().joined())
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());
	}

	private FeedPage page(List<PostView> views, List<Post> fetched, int limit) {
		String nextCursor = fetched.size() == limit
			? cursorCodec.encode(fetched.get(fetched.size() - 1).createdAt()) : null;
		return new FeedPage(views, nextCursor);
	}

	private int normalizeLimit(int limit) {
		if (limit < 1) {
			return 20;
		}
		return Math.min(limit, 50);
	}
}

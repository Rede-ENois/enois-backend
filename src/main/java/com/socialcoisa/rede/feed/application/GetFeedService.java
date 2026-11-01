package com.socialcoisa.rede.feed.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import com.socialcoisa.rede.corporate.domain.CorporateProfile;
import com.socialcoisa.rede.feed.domain.AuthorIdentity;
import com.socialcoisa.rede.feed.domain.FeedItem;
import com.socialcoisa.rede.feed.domain.FeedTab;
import com.socialcoisa.rede.feed.domain.Post;
import com.socialcoisa.rede.feed.domain.PostId;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.profile.application.ContributorTagView;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.contributions.application.port.out.UserSealRepository;
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
	private final UserSealRepository userSeals;
	private final PostInteractionRepository postInteractionRepository;
	private final CommentRepository commentRepository;
	private final CursorCodec cursorCodec;
	private final BlockedUsersReader blockedUsersReader;
	private final PostCommunityReader postCommunityReader;
	private final com.socialcoisa.rede.feed.application.port.out.CommunityChannelReader communityChannelReader;
	private final SubscribedBlogAuthorsReader subscribedBlogAuthors;
	private final com.socialcoisa.rede.corporate.application.port.out.CorporateProfileRepository corporateProfiles;
	private final com.socialcoisa.rede.feed.application.port.out.ReaderPreferencesReader readerPreferences;
	private final PostNoteService postNotes;
	private final com.socialcoisa.rede.feed.application.port.out.PollRepository polls;
	private static final Instant DEFAULT_BEFORE = Instant.parse("9999-12-31T23:59:59Z");

	public GetFeedService(PostRepository postRepository, ArticleRepository articleRepository,
			FeedCacheRepository feedCacheRepository,
			ConnectionGraphRepository connectionGraphRepository, UserProfileRepository userProfileRepository,
			UserSealRepository userSeals,
			PostInteractionRepository postInteractionRepository, CommentRepository commentRepository,
			CursorCodec cursorCodec, BlockedUsersReader blockedUsersReader, PostCommunityReader postCommunityReader,
			com.socialcoisa.rede.feed.application.port.out.CommunityChannelReader communityChannelReader,
			SubscribedBlogAuthorsReader subscribedBlogAuthors,
			com.socialcoisa.rede.corporate.application.port.out.CorporateProfileRepository corporateProfiles,
			com.socialcoisa.rede.feed.application.port.out.ReaderPreferencesReader readerPreferences,
			PostNoteService postNotes,
			com.socialcoisa.rede.feed.application.port.out.PollRepository polls) {
		this.postRepository = postRepository;
		this.articleRepository = articleRepository;
		this.feedCacheRepository = feedCacheRepository;
		this.connectionGraphRepository = connectionGraphRepository;
		this.userProfileRepository = userProfileRepository;
		this.userSeals = userSeals;
		this.postInteractionRepository = postInteractionRepository;
		this.commentRepository = commentRepository;
		this.cursorCodec = cursorCodec;
		this.blockedUsersReader = blockedUsersReader;
		this.postCommunityReader = postCommunityReader;
		this.communityChannelReader = communityChannelReader;
		this.subscribedBlogAuthors = subscribedBlogAuthors;
		this.corporateProfiles = corporateProfiles;
		this.readerPreferences = readerPreferences;
		this.postNotes = postNotes;
		this.polls = polls;
	}

	private static final long TRENDING_WINDOW_HOURS = 24;

	@Override
	@Transactional(readOnly = true)
	public FeedPage getFeed(GetFeedQuery query) {
		int limit = normalizeLimit(query.limit());
		Instant before = query.before().orElse(DEFAULT_BEFORE);
		FeedTab tab = normalizeTab(query.tab());

		if (tab == FeedTab.FOR_YOU) {
			return forYou(query, before, limit);
		}
		List<Post> posts = switch (tab) {
			case FOR_YOU -> throw new IllegalStateException();
			case ALL -> {
				// Filtro por tipo de post (opcional): se o frontend mandar types, filtra.
				if (query.types() != null && !query.types().isEmpty()) {
					yield postRepository.findActiveByTypes(query.types(), query.resolved(), before, limit);
				}
				Collection<String> blocked = readerPreferences.blockedTopics(query.userId().value());
				yield blocked.isEmpty() ? postRepository.findActiveAll(before, limit)
					: postRepository.findActiveAllExcludingTopics(blocked, before, limit);
			}
			case UNSEEN -> {
				Collection<String> bloqueados = readerPreferences.blockedTopics(query.userId().value());
				int janela = Math.max(limit * 5, 60);
				List<Post> candidatos = bloqueados.isEmpty()
					? postRepository.findActiveAll(before, janela)
					: postRepository.findActiveAllExcludingTopics(bloqueados, before, janela);
				Set<String> comentados = new LinkedHashSet<>(
					commentRepository.findPostIdsByAuthor(query.userId().value()));
				Set<String> reagidos = postInteractionRepository
					.findReactionsByUserAndPostIds(query.userId(),
						candidatos.stream().map(Post::id).toList())
					.keySet().stream().map(PostId::value).collect(Collectors.toSet());
				yield candidatos.stream()
					.filter(p -> !p.authorId().value().equals(query.userId().value()))
					.filter(p -> !comentados.contains(p.id().value()))
					.filter(p -> !reagidos.contains(p.id().value()))
					.limit(limit)
					.toList();
			}
			case TRENDING -> {
				Instant since = java.time.Clock.systemUTC().instant()
					.minus(java.time.Duration.ofHours(TRENDING_WINDOW_HOURS));
				yield postRepository.findTrending(since, limit);
			}
			case INTERACTED -> {
				// Post IDs onde o usuario reagiu ou comentou, unificados e ordenados.
				int cap = limit * 3;
				Set<String> interacted = new LinkedHashSet<>(
					postInteractionRepository.findReactedPostIdsByUserId(query.userId(), cap));
				interacted.addAll(commentRepository.findPostIdsByAuthor(query.userId().value()));
				if (interacted.isEmpty()) {
					yield List.<Post>of();
				}
				List<PostId> postIds = interacted.stream().map(PostId::new).toList();
				List<Post> found = postRepository.findByIds(postIds).stream()
					.filter(Post::isActive)
					.filter(p -> p.createdAt().isBefore(before))
					.sorted(Comparator.comparing(Post::createdAt).reversed())
					.limit(limit)
					.toList();
				yield found;
			}
			// Legado: tabs por tipo que o frontend antigo ainda pode mandar.
			case PROJECTS -> postRepository.findActiveByType(PostType.PROJECT, before, limit);
			case NEWS -> postRepository.findActiveByType(PostType.NEWS, before, limit);
			case TUTORIALS -> postRepository.findActiveByType(PostType.TUTORIAL, before, limit);
			case OPPORTUNITIES -> postRepository.findActiveByType(PostType.OPPORTUNITY, before, limit);
			case REVIEWS -> postRepository.findActiveByType(PostType.REVIEW, before, limit);
			case FUN -> postRepository.findActiveByType(PostType.DIVERTIDO, before, limit);
			// Legado: FOLLOWING e DISCUSSIONS mapeados pro FOR_YOU.
			case FOLLOWING, DISCUSSIONS, RECENT -> {
				Set<UserId> authors = new LinkedHashSet<>();
				authors.add(query.userId());
				authors.addAll(connectionGraphRepository.findFriends(query.userId()));
				yield postRepository.findActiveByAuthors(authors, before, limit);
			}
		};
		return page(enrich(posts, query.userId()), posts, limit);
	}

	/** Mapeia tabs legados pros novos. */
	private static FeedTab normalizeTab(FeedTab tab) {
		if (tab == null) return FeedTab.FOR_YOU;
		return tab;
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

	/**
	 * Feed de um canal privado: só membros da comunidade. O post de canal nunca aparece nos outros
	 * feeds (as consultas deles filtram `channel_id is null`).
	 */
	@Transactional(readOnly = true)
	public FeedPage getChannelFeed(String communityId, String channelId, Instant before, int limit,
			UserId currentUserId) {
		limit = normalizeLimit(limit);
		before = before == null ? DEFAULT_BEFORE : before;
		String viewerId = currentUserId == null ? null : currentUserId.value();
		PostCommunityReader.CommunityRef community = postCommunityReader.byIds(List.of(communityId), viewerId)
			.get(communityId);
		if (community == null) {
			throw new com.socialcoisa.rede.shared.web.ApiException(404, "COMMUNITY_NOT_FOUND",
				"Comunidade nao encontrada.");
		}
		if (!community.joined()) {
			throw new com.socialcoisa.rede.shared.web.ApiException(403, "NOT_A_MEMBER",
				"Os canais sao privados: so quem participa da comunidade pode ver.");
		}
		var channel = communityChannelReader.byId(channelId)
			.filter(found -> found.communityId().equals(communityId))
			.orElseThrow(() -> new com.socialcoisa.rede.shared.web.ApiException(404, "CHANNEL_NOT_FOUND",
				"Canal nao encontrado nesta comunidade."));
		List<Post> posts = postRepository.findActiveByChannel(channel.id(), before, limit);
		return page(enrich(posts, currentUserId), posts, limit);
	}

	@Transactional(readOnly = true)
	public FeedPage getCommunityFeed(String communityId, Instant before, int limit, UserId currentUserId) {
		limit = normalizeLimit(limit);
		before = before == null ? DEFAULT_BEFORE : before;
		List<Post> posts = postRepository.findActiveByCommunity(communityId, before, limit);
		return page(enrich(posts, currentUserId), posts, limit);
	}

	/**
	 * Feed corporativo: somente o que as identidades profissionais assinam (BUSINESS). Consulta
	 * própria, no banco — nada de buscar o feed social e filtrar fora.
	 */
	@Transactional(readOnly = true)
	public FeedPage getCorporateFeed(com.socialcoisa.rede.feed.domain.UserId viewer, Instant before, int limit) {
		limit = normalizeLimit(limit);
		before = before == null ? DEFAULT_BEFORE : before;
		List<Post> posts = postRepository.findActiveCorporate(before, limit);
		return page(enrich(posts, viewer), posts, limit);
	}

	/**
	 * Página do perfil profissional (endereço do perfil): somente o que o dono assinou como
	 * CORPORATE. Perfil desativado ou inexistente não tem página pública (404).
	 */
	@Transactional(readOnly = true)
	public FeedPage getCorporateProfilePosts(String profileUsername, Instant before, int limit,
			com.socialcoisa.rede.feed.domain.UserId viewer) {
		limit = normalizeLimit(limit);
		before = before == null ? DEFAULT_BEFORE : before;
		CorporateProfile profile = corporateProfiles
			.findByUsername(profileUsername == null ? "" : profileUsername.trim().toLowerCase())
			.filter(p -> p.active())
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil profissional não encontrado."));
		List<Post> posts = postRepository.findActiveCorporateByAuthor(
			new com.socialcoisa.rede.feed.domain.UserId(profile.userId()), before, limit);
		return page(enrich(posts, viewer), posts, limit);
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
		// Quem tem selo ativo ganha a tag "Contribuinte" junto do nome, em lote.
		Set<String> contributorIds = userSeals.findActiveUserIds(profiles.keySet());
		// A apresentação de quem assinou: post CORPORATE mostra o perfil profissional (nome, @,
		// avatar), nunca a identidade pessoal. Uma consulta em lote para a página inteira.
		Set<String> corporateAuthorIds = visible.stream()
			.filter(p -> p.authorIdentity() == AuthorIdentity.CORPORATE)
			.map(p -> p.authorId().value())
			.collect(Collectors.toSet());
		Map<String, CorporateProfile> corporate = corporateAuthorIds
			.isEmpty() ? Map.of()
			: corporateProfiles.findByUserIds(corporateAuthorIds).stream()
				.collect(Collectors.toMap(CorporateProfile::userId,
					Function.identity()));
		// Comunidade dos posts da pagina: nome para o "publicou em ..." e se o leitor ja participa
		// (a consulta e em lote, uma para a pagina inteira).
		java.util.Set<String> communityIds = visible.stream()
			.map(Post::communityId)
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toSet());
		Map<String, PostCommunityReader.CommunityRef> communities = postCommunityReader
			.byIds(communityIds, currentUserId == null ? null : currentUserId.value());

		List<PostId> postIds = visible.stream().map(Post::id).toList();
		List<String> postIdValues = postIds.stream().map(PostId::value).toList();
		Map<String, List<PostNoteView>> notesByPost = postNotes.viewsByPost(postIdValues, hidden);
		Map<String, MyPostNoteView> myNotes = currentUserId == null ? Map.of()
			: postNotes.mineByPost(currentUserId.value(), postIdValues);
		// Sem sessao (link compartilhado aberto por quem recebeu) nao ha reacao do visitante para
		// buscar: a leitura publica nao pode depender de um usuario logado.
		Map<PostId, ReactionType> currentUserReactions = currentUserId == null ? Map.of()
			: postInteractionRepository.findReactionsByUserAndPostIds(currentUserId, postIds);
		// Reaction pills for the whole page in one query: the card used to fetch them per post.
		Map<PostId, Map<ReactionType, Long>> reactionSummaries = postInteractionRepository
			.countReactionsByPostIds(postIds, hidden);
		// Enquete: alternativas, contagem e o voto de quem le, em lote para a página inteira.
		Map<String, List<String>> pollOptions = polls.optionsByPostIds(postIdValues);
		Map<String, Map<Integer, Long>> pollCounts = pollOptions.isEmpty() ? Map.of()
			: polls.countsByPostIds(pollOptions.keySet());
		Map<String, Integer> myVotes = currentUserId == null || pollOptions.isEmpty() ? Map.of()
			: polls.votesByUserAndPostIds(currentUserId, pollOptions.keySet());
		// Citação do repost com opinião: busca os originais da página em UMA consulta.
		java.util.Set<String> quotedIds = visible.stream()
			.filter(p -> p.repostWithOpinion() && p.repostOf() != null)
			.map(Post::repostOf)
			.collect(Collectors.toSet());
		Map<String, Post> quotedPosts = quotedIds.isEmpty() ? Map.of()
			: postRepository.findByIds(quotedIds.stream().map(PostId::new).toList()).stream()
				.collect(Collectors.toMap(p -> p.id().value(), Function.identity()));
		return visible.stream()
			.map(post -> {
				UserProfile profile = profiles.get(post.authorId().value());
				var corporateProfile = corporate.get(post.authorId().value());
				PostView view = PostView.from(post,
					corporateProfile != null ? corporateProfile.username()
						: profile != null ? profile.username() : null,
					corporateProfile != null ? corporateProfile.name()
						: profile != null ? profile.displayWithNickname() : post.authorId().value(),
					corporateProfile != null ? corporateProfile.avatar()
						: profile != null ? profile.avatar() : null,
					profile != null ? profile.allBadges() : List.of())
					.withAvatarFrame(profile != null ? profile.avatarFrame() : null)
					.withAuthorStyle(corporateProfile == null ? NameStyleView.from(profile) : null)
					.withContributor(corporateProfile == null && contributorIds.contains(post.authorId().value())
						? ContributorTagView.from(profile) : null);
				ReactionType reaction = currentUserReactions.get(post.id());
				PostView withReaction = (reaction != null ? view.withCurrentUserReaction(reaction) : view)
					.withReactionSummary(reactionSummaries.getOrDefault(post.id(), Map.of()));
				if (post.repostWithOpinion() && post.repostOf() != null) {
					Post quoted = quotedPosts.get(post.repostOf());
					if (quoted != null) {
						withReaction = withReaction.withRepostQuote(quoted.content(), quoted.mediaIds());
					}
				}
				PostCommunityReader.CommunityRef community = post.communityId() == null ? null
					: communities.get(post.communityId());
				PostView withCommunity = community == null ? withReaction
					: withReaction.withCommunity(community.id(),
						new PostView.CommunityView(community.id(), community.name(), community.joined()));
				PostView withNotesView = withCommunity.withNotes(
					notesByPost.getOrDefault(post.id().value(), List.of()), myNotes.get(post.id().value()));
				List<String> options = pollOptions.get(post.id().value());
				if (options == null) {
					return withNotesView;
				}
				Map<Integer, Long> counts = pollCounts.getOrDefault(post.id().value(), Map.of());
				List<Long> perOption = new java.util.ArrayList<>(options.size());
				for (int position = 0; position < options.size(); position++) {
					perOption.add(counts.getOrDefault(position, 0L));
				}
				long total = perOption.stream().mapToLong(Long::longValue).sum();
				return withNotesView.withPoll(new PollView(options, perOption,
					myVotes.get(post.id().value()), total));
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
			case FOR_YOU, FOLLOWING, ALL, UNSEEN, TRENDING, INTERACTED, DISCUSSIONS, RECENT -> true;
			case PROJECTS -> post.type() == PostType.PROJECT;
			case NEWS -> post.type() == PostType.NEWS;
			case TUTORIALS -> post.type() == PostType.TUTORIAL;
			case OPPORTUNITIES -> post.type() == PostType.OPPORTUNITY;
			case REVIEWS -> post.type() == PostType.REVIEW;
			case FUN -> post.type() == PostType.DIVERTIDO;
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
		Collection<String> blocked = readerPreferences.blockedTopics(query.userId().value());
		List<Post> fetched = blocked.isEmpty()
			? postRepository.findActiveByAuthors(authors, before, limit * 3)
			: postRepository.findActiveByAuthorsExcludingTopics(authors, blocked, before, limit * 3);
		Set<String> hiddenCommunities = communityIdsWithoutMembership(fetched, query.userId());
		List<Post> filtered = fetched.stream()
			.filter(post -> post.communityId() == null || !hiddenCommunities.contains(post.communityId()))
			.toList();
		// The page is a DATE WINDOW: the `limit` newest visible posts, and the cursor is the oldest
		// of them (or of the raw fetch, when the filter left fewer than `limit`). The relevance boost
		// below only reorders inside the window, so no post is skipped or repeated across pages, and
		// a later page never holds a post newer than an earlier one (the front's "voce esta em dia"
		// section relies on that).
		List<Post> visible = filtered.stream().limit(limit).toList();
		String nextCursor = filtered.size() > limit
			? cursorCodec.encode(visible.get(visible.size() - 1).createdAt())
			: fetched.size() == limit * 3 ? cursorCodec.encode(fetched.get(fetched.size() - 1).createdAt()) : null;
		// "O que quero ver" is a priority boost, not a filter: inside the page, posts on the chosen
		// topics come first and the rest stays newest first. Nothing leaves the feed.
		Collection<String> liked = readerPreferences.likedTopics(query.userId().value());
		if (!liked.isEmpty() && visible.size() > 1) {
			// topic() is null for posts without a topic, and liked is immutable: contains(null) throws.
			// Only the date is reversed (newest first); the boost keeps liked topics on top.
			visible = visible.stream()
				.sorted(java.util.Comparator
					.comparing((Post post) -> post.topic() != null && liked.contains(post.topic()) ? 0 : 1)
					.thenComparing(Post::createdAt, java.util.Comparator.reverseOrder()))
				.toList();
		}
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

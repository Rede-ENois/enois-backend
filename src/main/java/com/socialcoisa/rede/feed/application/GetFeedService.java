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
	private final com.socialcoisa.rede.feed.application.port.out.PostRepostRepository postReposts;
	private final com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase mediaSummaries;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate;

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
			com.socialcoisa.rede.feed.application.port.out.PollRepository polls,
			com.socialcoisa.rede.feed.application.port.out.PostRepostRepository postReposts,
			com.socialcoisa.rede.media.application.port.in.GetMediaSummariesUseCase mediaSummaries,
			com.socialcoisa.rede.shared.sanctions.SanctionGateReader sanctionGate) {
		this.mediaSummaries = mediaSummaries;
		this.sanctionGate = sanctionGate;
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
		this.postReposts = postReposts;
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
		if (tab == FeedTab.TRENDING) {
			return trending(query, limit);
		}
		List<Post> posts = switch (tab) {
			case FOR_YOU, TRENDING -> throw new IllegalStateException();
			case ALL -> {
				// Filtro por tipo de post (opcional): se o frontend mandar types, filtra.
				if (query.types() != null && !query.types().isEmpty()) {
					yield postRepository.findActiveByTypes(query.types(), query.resolved(), before, limit);
				}
				Collection<String> blocked = readerPreferences.blockedTopics(query.userId().value());
				yield blocked.isEmpty() ? postRepository.findActiveAll(before, limit)
					: postRepository.findActiveAllExcludingTopics(blocked, before, limit);
			}
			case UNSEEN -> postRepository.findActiveUnseen(query.userId(),
				readerPreferences.blockedTopics(query.userId().value()), before, limit);
			case INTERACTED -> postRepository.findActiveInteracted(query.userId(), before, limit);
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
		// "Ocultar reposts": fora do feed pessoal (repost puro tem repostOf nulo no post —
		// o ponteiro mora em post_reposts; já o repost com opinião é um post com repostOf).
		// O cursor continua saindo da janela do banco: filtrar a lista exibida não pode
		// encerrar a paginação antes da hora.
		List<Post> window = posts;
		if (readerPreferences.hideReposts(query.userId().value())) {
			posts = posts.stream().filter(post -> post.repostOf() == null).toList();
		}
		return page(enrich(posts, query.userId()), window, limit);
	}

	private FeedPage trending(GetFeedQuery query, int limit) {
		TrendingCursor after = query.trendingCursor();
		Instant until = after == null ? java.time.Clock.systemUTC().instant() : after.windowEnd();
		List<Post> fetched = postRepository.findTrending(
			until.minus(java.time.Duration.ofHours(TRENDING_WINDOW_HOURS)), until, after, limit + 1);
		List<Post> window = fetched.stream().limit(limit).toList();
		List<Post> posts = window;
		if (readerPreferences.hideReposts(query.userId().value())) {
			posts = posts.stream().filter(post -> post.repostOf() == null).toList();
		}
		String nextCursor = null;
		if (fetched.size() > limit) {
			// O cursor sai da última chave da JANELA, não da lista exibida: uma página só de
			// reposts escondidos fica vazia, mas ainda aponta para a continuação.
			Post last = window.getLast();
			nextCursor = new TrendingCursor(until, last.counters().reactions(), last.createdAt(), last.id().value()).encode();
		}
		return new FeedPage(enrich(posts, query.userId()), nextCursor);
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
		// Banned account: its page is gone (same rule as the profile read), and so is its timeline.
		if (sanctionGate.banned(authorId)) {
			throw new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		List<Post> authored = postRepository.findActiveByAuthor(new UserId(authorId), before, limit);

		// Reposts puros: ponteiros na tabela post_reposts com a data do repost (nao do original).
		List<com.socialcoisa.rede.feed.domain.PostRepost> reposts = postReposts.findByUserId(authorId, before, limit);
		java.util.Map<String, Instant> repostDates = reposts.stream()
			.collect(Collectors.toMap(com.socialcoisa.rede.feed.domain.PostRepost::postId,
				com.socialcoisa.rede.feed.domain.PostRepost::createdAt));
		List<String> repostedIds = reposts.stream().map(com.socialcoisa.rede.feed.domain.PostRepost::postId).toList();
		List<Post> repostedPosts = repostedIds.isEmpty() ? List.of()
			: postRepository.findByIds(repostedIds.stream().map(PostId::new).toList()).stream()
				.filter(Post::isActive)
				.toList();

		// Mescla posts autorais e repostados, sem duplicatas, ordenados pela data efetiva
		// (data do repost para reposts, data de criacao para posts autorais).
		Set<String> authoredIds = authored.stream().map(p -> p.id().value()).collect(Collectors.toSet());
		List<Post> merged = new ArrayList<>(authored);
		for (Post rp : repostedPosts) {
			if (!authoredIds.contains(rp.id().value())) {
				merged.add(rp);
			}
		}
		merged.sort(Comparator.comparing(
			(Post p) -> repostDates.getOrDefault(p.id().value(), p.createdAt())).reversed());
		List<Post> page = merged.stream().limit(limit).toList();

		// Enriquece e marca os reposts com o badge "X republicou" e a data do repost.
		List<PostView> enriched = enrich(page, query.viewerId());
		UserProfile ownerProfile = userProfileRepository.findById(authorId).orElse(null);
		if (ownerProfile != null && !repostedIds.isEmpty()) {
			Set<String> repostedSet = new java.util.HashSet<>(repostedIds);
			enriched = enriched.stream()
				.map(v -> repostedSet.contains(v.id()) && !v.authorId().equals(authorId)
					? v.withRepostedBy(new PostView.RepostedByView(
						ownerProfile.displayWithNickname(), ownerProfile.username(), ownerProfile.avatar(),
						repostDates.get(v.id())))
					: v)
				.toList();
		}

		// Cursor pela data efetiva do ultimo item (repost date ou created date).
		Instant lastEffective = page.isEmpty() ? null
			: repostDates.getOrDefault(page.get(page.size() - 1).id().value(),
				page.get(page.size() - 1).createdAt());
		String nextCursor = page.size() == limit && lastEffective != null
			? cursorCodec.encode(lastEffective) : null;
		return new FeedPage(enriched, nextCursor);
	}

	@Override
	@Transactional(readOnly = true)
	public PostView getPost(String postId, UserId viewerId) {
		Post post = visiblePost(postId, viewerId);
		List<PostView> enriched = enrich(List.of(post), viewerId);
		// Unico lugar que devolve o corpo da pagina e o texto do artigo (ver PostView.from): e a
		// leitura de um post so — a listagem nao carrega nem um nem outro.
		return (enriched.isEmpty() ? PostView.from(post) : enriched.get(0))
			.withPageHtml(post.pageHtml())
			.withArticleBody(articleRepository.findBody(post.id()).orElse(null));
	}

	@Override
	@Transactional(readOnly = true)
	public void requireVisible(String postId, UserId viewerId) {
		visiblePost(postId, viewerId);
	}

	/**
	 * Carrega o post e aplica as regras de visibilidade. Post de canal privado nao e publico: sem
	 * sessao, ou com sessao de quem nao participa da comunidade, a resposta e 404 — o mesmo que um
	 * id inexistente, para nao confirmar a existencia do conteudo.
	 */
	private Post visiblePost(String postId, UserId viewerId) {
		Post post = postRepository.findById(new PostId(postId))
			.filter(Post::isActive)
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado."));
		// Link direto (notificacao antiga, por exemplo) tambem respeita o bloqueio.
		if (viewerId != null && blockedUsersReader.hiddenIdsFor(viewerId.value()).contains(post.authorId().value())) {
			throw new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado.");
		}
		if (post.channelId() != null) {
			PostCommunityReader.CommunityRef community = post.communityId() == null || viewerId == null ? null
				: postCommunityReader.byIds(List.of(post.communityId()), viewerId.value()).get(post.communityId());
			if (community == null || !community.joined()) {
				throw new ApiException(404, "POST_NOT_FOUND", "Post nao encontrado.");
			}
		}
		return post;
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
			.filter(p -> !sanctionGate.banned(p.userId()))
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil profissional não encontrado."));
		List<Post> posts = postRepository.findActiveCorporateByAuthor(
			new com.socialcoisa.rede.feed.domain.UserId(profile.userId()), before, limit);
		return page(enrich(posts, viewer), posts, limit);
	}

	private List<PostView> enrich(List<Post> posts, UserId currentUserId) {
		return enrich(posts, currentUserId, null);
	}

	/**
	 * Versão com o conjunto de bloqueios já em mão: o "Para você" busca o conjunto uma vez e o
	 * reusa no enrich da página e no enrich dos reposts mesclados — sem repetir as consultas de
	 * bloqueio/mute a cada chamada. Null busca por conta própria (todos os outros caminhos).
	 */
	private List<PostView> enrich(List<Post> posts, UserId currentUserId, Set<String> knownHidden) {
		if (posts.isEmpty()) {
			return List.of();
		}
		Set<String> hidden = knownHidden != null ? knownHidden
			: blockedUsersReader.hiddenIdsFor(currentUserId != null ? currentUserId.value() : null);
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
		// Reposts puros do viewer: batch check para marcar currentUserReposted.
		Set<String> repostedByViewer = currentUserId == null ? Set.of()
			: postReposts.findPostIdsRepostedByUser(currentUserId.value(), postIdValues);
		// Citação do repost com opinião: busca os originais da página em UMA consulta.
		java.util.Set<String> quotedIds = visible.stream()
			.filter(p -> p.repostWithOpinion() && p.repostOf() != null)
			.map(Post::repostOf)
			.collect(Collectors.toSet());
		Map<String, Post> quotedPosts = quotedIds.isEmpty() ? Map.of()
			: postRepository.findByIds(quotedIds.stream().map(PostId::new).toList()).stream()
				.collect(Collectors.toMap(p -> p.id().value(), Function.identity()));
		// Autores dos posts citados: busca em lote pra exibir o avatar no crédito do repost.
		// Variável final porque o lambda do stream abaixo referencia ela.
		java.util.Set<String> quotedAuthorIds = quotedPosts.values().stream()
			.map(p -> p.authorId().value())
			.filter(id -> !profiles.containsKey(id))
			.collect(Collectors.toSet());
		final Map<String, UserProfile> allProfiles;
		if (quotedAuthorIds.isEmpty()) {
			allProfiles = profiles;
		} else {
			allProfiles = new java.util.HashMap<>(profiles);
			userProfileRepository.findAllByIds(quotedAuthorIds)
				.forEach(p -> allProfiles.put(p.id(), p));
		}
		// Attachments of the whole page (address, type, pixel size) in ONE query, so the card draws
		// at its final height instead of fetching each media and growing after it loads.
		Map<String, com.socialcoisa.rede.media.application.MediaSummaryView> mediaById = mediaSummaries
			.visibleByIds(visible.stream().flatMap(p -> p.mediaIds().stream()).collect(Collectors.toSet()));
		return visible.stream()
			.map(post -> {
				UserProfile profile = allProfiles.get(post.authorId().value());
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
						UserProfile quotedAuthor = allProfiles.get(quoted.authorId().value());
						withReaction = withReaction.withRepostQuote(quoted.content(), quoted.mediaIds(),
							quotedAuthor != null ? quotedAuthor.avatar() : null);
					}
				}
				PostCommunityReader.CommunityRef community = post.communityId() == null ? null
					: communities.get(post.communityId());
				PostView withCommunity = community == null ? withReaction
					: withReaction.withCommunity(community.id(),
						new PostView.CommunityView(community.id(), community.name(), community.joined()));
				PostView withNotesView = withCommunity.withNotes(
					notesByPost.getOrDefault(post.id().value(), List.of()), myNotes.get(post.id().value()));
				boolean userReposted = repostedByViewer.contains(post.id().value());
				PostView withRepostedFlag = userReposted ? withNotesView.withCurrentUserReposted(true) : withNotesView;
				List<String> options = pollOptions.get(post.id().value());
				if (options == null) {
					return withRepostedFlag;
				}
				Map<Integer, Long> counts = pollCounts.getOrDefault(post.id().value(), Map.of());
				List<Long> perOption = new java.util.ArrayList<>(options.size());
				for (int position = 0; position < options.size(); position++) {
					perOption.add(counts.getOrDefault(position, 0L));
				}
				long total = perOption.stream().mapToLong(Long::longValue).sum();
				return withRepostedFlag.withPoll(new PollView(options, perOption,
					myVotes.get(post.id().value()), total));
			})
			.map(view -> view.withMedia(mediaById))
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
		// "Ocultar reposts": o repost com opinião sai da janela do "Para você" e a mesclagem
		// de reposts puros dos amigos (abaixo) é pulada inteira.
		boolean hideReposts = readerPreferences.hideReposts(query.userId().value());
		// O conjunto de bloqueios + mutes vale a requisição inteira: o enrich() das duas listas
		// (página e reposts mesclados) e a mesclagem leem dele. Antes eram duas leituras por rodada
		// (3 consultas cada) na rota mais aberta do app (#517).
		Set<String> hiddenForRequest = blockedUsersReader.hiddenIdsFor(query.userId().value());
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
		if (hideReposts) {
			visible = visible.stream().filter(post -> post.repostOf() == null).toList();
		}
		// O cursor sai da janela (posição `limit` de `filtered`), não da lista exibida: uma janela
		// só de reposts escondidos deixa a página vazia, mas precisa continuar para a próxima.
		String nextCursor = filtered.size() > limit
			? cursorCodec.encode(filtered.get(limit - 1).createdAt())
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
		List<PostView> enriched = enrich(visible, query.userId(), hiddenForRequest);

		// Reposts puros dos amigos: uma consulta em batch (todos os amigos de uma vez).
		// Filtros do "Para você" valem: comunidade sem participacao, topico bloqueado e bloqueio.
		// "Ocultar reposts" liga os três de uma vez: nem o post repostado entra.
		Set<String> alreadyInFeed = visible.stream().map(p -> p.id().value()).collect(Collectors.toSet());
		Set<String> hidden = hiddenForRequest;
		List<String> friendIdList = authors.stream().map(UserId::value)
			.filter(id -> !id.equals(query.userId().value())).toList();
		// Uma consulta batch: todos os reposts recentes dos amigos.
		java.util.Map<String, java.util.List<com.socialcoisa.rede.feed.domain.PostRepost>> allReposts =
			postReposts.findRepostsByUserIds(friendIdList, limit);
		// Agrupa por post ID, guardando o repost mais recente (determinístico).
		java.util.Map<String, com.socialcoisa.rede.feed.domain.PostRepost> bestRepost = new java.util.LinkedHashMap<>();
		for (var entry : allReposts.entrySet()) {
			for (var repost : entry.getValue()) {
				if (alreadyInFeed.contains(repost.postId())) continue;
				if (hidden.contains(repost.userId())) continue;
				var existing = bestRepost.get(repost.postId());
				if (existing == null || (repost.createdAt() != null && existing.createdAt() != null
						&& repost.createdAt().isAfter(existing.createdAt()))) {
					bestRepost.put(repost.postId(), repost);
				}
			}
		}
		if (!hideReposts && !bestRepost.isEmpty()) {
			List<Post> repostedPosts = postRepository.findByIds(
				bestRepost.keySet().stream().map(PostId::new).toList()).stream()
				.filter(Post::isActive)
				// Mesmos filtros do "Para você": sem comunidade alheia, sem topico bloqueado.
				.filter(p -> p.communityId() == null || !hiddenCommunities.contains(p.communityId()))
				.filter(p -> blocked.isEmpty() || p.topic() == null || !blocked.contains(p.topic()))
				.toList();
			List<PostView> repostedViews = enrich(repostedPosts, query.userId(), hiddenForRequest);
			java.util.Map<String, com.socialcoisa.rede.profile.domain.UserProfile> repostProfiles =
				userProfileRepository.findAllByIds(bestRepost.values().stream()
					.map(com.socialcoisa.rede.feed.domain.PostRepost::userId).collect(Collectors.toSet()))
					.stream().collect(Collectors.toMap(
						com.socialcoisa.rede.profile.domain.UserProfile::id,
						java.util.function.Function.identity()));
			enriched = new java.util.ArrayList<>(enriched);
			for (PostView view : repostedViews) {
				var repost = bestRepost.get(view.id());
				if (repost == null) continue;
				var reposter = repostProfiles.get(repost.userId());
				if (reposter != null) {
					enriched.add(view.withRepostedBy(new PostView.RepostedByView(
						reposter.name(), reposter.username(), reposter.avatar(), repost.createdAt())));
				}
			}
			enriched.sort(java.util.Comparator.comparing(
				(PostView v) -> v.repostedBy() != null && v.repostedBy().at() != null
					? v.repostedBy().at() : v.createdAt()).reversed());
		}

		return new FeedPage(enriched, nextCursor);
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

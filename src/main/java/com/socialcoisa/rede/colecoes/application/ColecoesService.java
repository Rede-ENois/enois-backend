package com.socialcoisa.rede.colecoes.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.colecoes.application.ColecoesViews.ColecaoResumo;
import com.socialcoisa.rede.colecoes.application.ColecoesViews.ColecoesPage;
import com.socialcoisa.rede.colecoes.application.ColecoesViews.ColecaoPostsPage;
import com.socialcoisa.rede.colecoes.application.ColecoesViews.PostSalvoView;
import com.socialcoisa.rede.colecoes.application.port.out.ColecoesRepository;
import com.socialcoisa.rede.colecoes.domain.ColecaoPost;
import com.socialcoisa.rede.colecoes.domain.ColecaoSalva;
import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.port.in.GetPostsUseCase;
import com.socialcoisa.rede.feed.application.port.out.PostInteractionRepository;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Regras das Coleções pessoais. A coleção é a pasta em que o dono organiza os posts que JÁ
 * salvou: só ele vê e mexe, guardar exige o post estar salvo no perfil dele e visível para ele
 * (a mesma visibilidade do feed), e o post continua sendo ele mesmo — comentário, reação e
 * moderação seguem no feed. Coleção não é conteúdo público: nem notificação, nem moderação de
 * coleção; quem some é o post que sai do ar.
 */
@Service
public class ColecoesService {

	private static final int MAX_PAGE_SIZE = 48;
	private static final int MAX_TITULO = 120;
	private static final int MAX_DESCRICAO = 500;

	private final ColecoesRepository repository;
	private final GetPostsUseCase posts;
	private final PostInteractionRepository interactions;
	private final AgeGate agePolicy;
	private final SanctionGate sanctionGate;
	private final UserRateLimiter userRateLimiter;
	private final CursorCodec cursorCodec;
	private final Clock clock;

	public ColecoesService(ColecoesRepository repository, GetPostsUseCase posts, PostInteractionRepository interactions,
			AgeGate agePolicy, SanctionGate sanctionGate, UserRateLimiter userRateLimiter, CursorCodec cursorCodec,
			Clock clock) {
		this.repository = repository;
		this.posts = posts;
		this.interactions = interactions;
		this.agePolicy = agePolicy;
		this.sanctionGate = sanctionGate;
		this.userRateLimiter = userRateLimiter;
		this.cursorCodec = cursorCodec;
		this.clock = clock;
	}

	// ---- Escrita ----

	@Transactional
	public ColecaoResumo create(UserId ownerId, String title, String description) {
		agePolicy.requireCanWrite(ownerId.value());
		sanctionGate.requireNotRestricted(ownerId.value());
		userRateLimiter.require(ownerId.value(), UserRateLimiter.CREATE_POST);
		ColecaoSalva colecao = new ColecaoSalva("colecao_" + UUID.randomUUID().toString().replace("-", ""), ownerId,
			validarTitulo(title), validarDescricao(description), clock.instant());
		return resumo(repository.saveColecao(colecao), 0);
	}

	@Transactional
	public ColecaoResumo edit(UserId userId, String colecaoId, String title, String description) {
		ColecaoSalva colecao = propria(userId, colecaoId);
		colecao.edit(validarTitulo(title), validarDescricao(description));
		return resumo(repository.saveColecao(colecao),
			repository.countPosts(List.of(colecaoId)).getOrDefault(colecaoId, 0L));
	}

	/** Coleção é dado privado: remover apaga a pasta e os elos de verdade. */
	@Transactional
	public void removeOwn(UserId userId, String colecaoId) {
		propria(userId, colecaoId);
		repository.deletePostsByColecao(colecaoId);
		repository.deleteColecao(colecaoId);
	}

	@Transactional
	public PostSalvoView addPost(UserId userId, String colecaoId, String postId) {
		agePolicy.requireCanWrite(userId.value());
		sanctionGate.requireNotRestricted(userId.value());
		userRateLimiter.require(userId.value(), UserRateLimiter.CREATE_COMMENT);
		propria(userId, colecaoId);
		// O post precisa existir e ser visível para o dono — a coleção não vira vitrine de nada
		// que o feed não mostra. É a MESMA visibilidade de sempre (GetPostsUseCase).
		var visivel = posts.getPosts(List.of(postId), userId);
		if (visivel.isEmpty()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Publicação não encontrada ou invisível para você.");
		}
		// E precisa estar SALVA pelo dono: a coleção organiza salvos, não substitui o salvar.
		if (!interactions.findSavedPostIdsByUserId(userId).contains(postId)) {
			throw new ApiException(409, "NAO_SALVO", "Salve a publicação no perfil antes de guardá-la numa coleção.");
		}
		if (repository.findPost(colecaoId, postId).isPresent()) {
			throw new ApiException(409, "JA_NA_COLECAO", "Essa publicação já está nesta coleção.");
		}
		ColecaoPost elo = ColecaoPost.novo(colecaoId, postId, clock.instant());
		repository.savePost(elo);
		return new PostSalvoView(postId, visivel.get(0), elo.createdAt());
	}

	@Transactional
	public void removePost(UserId userId, String colecaoId, String postId) {
		propria(userId, colecaoId);
		ColecaoPost elo = repository.findPost(colecaoId, postId)
			.orElseThrow(() -> new ApiException(404, "POST_NAO_NA_COLECAO", "Essa publicação não está nesta coleção."));
		repository.deletePost(elo.id());
	}

	// ---- Leitura (sempre do próprio dono) ----

	/**
	 * As coleções de quem pergunta, com a marca de ONDE este post já está guardado — o seletor
	 * "guardar em coleção" do card do feed usa isto. Não diz se o post está salvo no perfil:
	 * quem orquestra o salvar antes de guardar é o front, com o estado que o card já tem.
	 */
	@Transactional(readOnly = true)
	public ColecoesViews.MinhasColecoesPage minhas(UserId ownerId, String postId) {
		List<ColecaoSalva> colecoes = repository.findAllByOwner(ownerId);
		if (colecoes.isEmpty()) {
			return new ColecoesViews.MinhasColecoesPage(List.of());
		}
		List<String> idsDasColecoes = colecoes.stream().map(ColecaoSalva::id).toList();
		Map<String, Long> contagens = repository.countPosts(idsDasColecoes);
		Set<String> comOPost = Set.copyOf(repository.findColecaoIdsComPost(postId, idsDasColecoes));
		List<ColecoesViews.ColecaoDoPost> items = colecoes.stream()
			.map(colecao -> new ColecoesViews.ColecaoDoPost(colecao.id(), colecao.title(), colecao.description(),
				contagens.getOrDefault(colecao.id(), 0L), comOPost.contains(colecao.id()), colecao.createdAt()))
			.toList();
		return new ColecoesViews.MinhasColecoesPage(items);
	}

	@Transactional(readOnly = true)
	public ColecoesPage list(UserId ownerId, Optional<Instant> before, int limit) {
		int tamanho = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		Instant marca = before.orElseGet(() -> clock.instant().plusSeconds(1));
		List<ColecaoSalva> colecoes = repository.findByOwnerBefore(ownerId, marca, tamanho);
		Map<String, Long> contagens = repository.countPosts(ids(colecoes));
		List<ColecaoResumo> items = colecoes.stream()
			.map(colecao -> resumo(colecao, contagens.getOrDefault(colecao.id(), 0L)))
			.toList();
		String nextCursor = colecoes.size() == tamanho
			? cursorCodec.encode(colecoes.get(colecoes.size() - 1).createdAt()) : null;
		return new ColecoesPage(items, nextCursor);
	}

	@Transactional(readOnly = true)
	public ColecaoResumo get(UserId ownerId, String colecaoId) {
		ColecaoSalva colecao = propria(ownerId, colecaoId);
		return resumo(colecao, repository.countPosts(List.of(colecaoId)).getOrDefault(colecaoId, 0L));
	}

	@Transactional(readOnly = true)
	public ColecaoPostsPage posts(UserId ownerId, String colecaoId, Optional<Instant> before, int limit) {
		propria(ownerId, colecaoId);
		int tamanho = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		Instant marca = before.orElseGet(() -> clock.instant().plusSeconds(1));
		List<ColecaoPost> elos = repository.findPostsByColecao(colecaoId, marca, tamanho);
		// Os posts vêm do feed com a MESMA visibilidade de sempre: post apagado ou invisível
		// simplesmente não aparece na coleção — o elo fica, a vitrine não.
		Map<String, PostView> visiveis = posts
			.getPosts(elos.stream().map(ColecaoPost::postId).toList(), ownerId).stream()
			.collect(Collectors.toMap(PostView::id, Function.identity()));
		List<PostSalvoView> items = elos.stream()
			.filter(elo -> visiveis.containsKey(elo.postId()))
			.map(elo -> new PostSalvoView(elo.postId(), visiveis.get(elo.postId()), elo.createdAt()))
			.toList();
		String nextCursor = elos.size() == tamanho ? cursorCodec.encode(elos.get(elos.size() - 1).createdAt()) : null;
		return new ColecaoPostsPage(items, nextCursor);
	}

	// ---- Internos ----

	/** Coleção é privada: coleção inexistente OU de outra pessoa devolve o MESMO 404 — a existência da coleção alheia não se confirma. */
	private ColecaoSalva propria(UserId userId, String colecaoId) {
		return repository.findColecaoById(colecaoId)
			.filter(colecao -> colecao.ownedBy(userId))
			.orElseThrow(() -> new ApiException(404, "COLECAO_NOT_FOUND", "Coleção não encontrada."));
	}

	private String validarTitulo(String title) {
		if (!StringUtils.hasText(title)) {
			throw new ApiException(400, "COLECAO_TITLE_REQUIRED", "Dê um título à coleção.");
		}
		String titulo = title.trim();
		if (titulo.length() > MAX_TITULO) {
			throw new ApiException(400, "COLECAO_TITLE_TOO_LONG",
				"O título aceita no máximo " + MAX_TITULO + " caracteres.");
		}
		return titulo;
	}

	private String validarDescricao(String description) {
		if (description == null) {
			return null;
		}
		String descricao = description.trim();
		if (descricao.isEmpty()) {
			return null;
		}
		if (descricao.length() > MAX_DESCRICAO) {
			throw new ApiException(400, "CONTENT_TOO_LONG",
				"A descrição aceita no máximo " + MAX_DESCRICAO + " caracteres.");
		}
		return descricao;
	}

	private ColecaoResumo resumo(ColecaoSalva colecao, long postCount) {
		return new ColecaoResumo(colecao.id(), colecao.title(), colecao.description(), postCount, colecao.createdAt());
	}

	private List<String> ids(List<ColecaoSalva> colecoes) {
		return colecoes.stream().map(ColecaoSalva::id).toList();
	}
}

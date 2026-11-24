package com.socialcoisa.rede.murais.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
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

import com.socialcoisa.rede.feed.application.PostView;
import com.socialcoisa.rede.feed.application.port.in.GetPostsUseCase;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.murais.application.MuraisViews.ContribuicaoView;
import com.socialcoisa.rede.murais.application.MuraisViews.MuralAutor;
import com.socialcoisa.rede.murais.application.MuraisViews.MuralResumo;
import com.socialcoisa.rede.murais.application.port.in.MuraisModerationPort;
import com.socialcoisa.rede.murais.application.port.out.MuraisRepository;
import com.socialcoisa.rede.murais.domain.Mural;
import com.socialcoisa.rede.murais.domain.MuralContribuicao;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CursorCodec;

/**
 * Regras dos Murais. O mural é um quadro de coleção: o dono cria, edita e remove o quadro;
 * qualquer pessoa cola publicações que JÁ existem no feed. A publicação continua sendo ela
 * mesma — comentário, reação e moderação dela seguem no feed; o mural só guarda o elo.
 */
@Service
public class MuraisService implements MuraisModerationPort {

	private static final int MAX_PAGE_SIZE = 48;
	private static final int MAX_TITULO = 120;
	private static final int MAX_BUSCA = 120;
	private static final int MAX_DESCRICAO = 500;

	private final MuraisRepository repository;
	private final GetPostsUseCase posts;
	private final UserProfileRepository profiles;
	private final NotificationService notifications;
	private final AgeGate agePolicy;
	private final SanctionGate sanctionGate;
	private final UserRateLimiter userRateLimiter;
	private final CursorCodec cursorCodec;
	private final Clock clock;

	public MuraisService(MuraisRepository repository, GetPostsUseCase posts, UserProfileRepository profiles,
			NotificationService notifications, AgeGate agePolicy, SanctionGate sanctionGate,
			UserRateLimiter userRateLimiter, CursorCodec cursorCodec, Clock clock) {
		this.repository = repository;
		this.posts = posts;
		this.profiles = profiles;
		this.notifications = notifications;
		this.agePolicy = agePolicy;
		this.sanctionGate = sanctionGate;
		this.userRateLimiter = userRateLimiter;
		this.cursorCodec = cursorCodec;
		this.clock = clock;
	}

	// ---- Escrita ----

	@Transactional
	public MuralResumo create(UserId ownerId, String title, String description) {
		agePolicy.requireCanWrite(ownerId.value());
		sanctionGate.requireNotRestricted(ownerId.value());
		userRateLimiter.require(ownerId.value(), UserRateLimiter.CREATE_POST);
		String titulo = validarTitulo(title);
		String descricao = validarDescricao(description);
		Mural mural = Mural.novo("mural_" + UUID.randomUUID().toString().replace("-", ""), ownerId, titulo, descricao,
			clock.instant());
		return resumo(repository.saveMural(mural), 0);
	}

	@Transactional
	public MuralResumo edit(UserId userId, String muralId, String title, String description) {
		Mural mural = ativo(muralId);
		if (!mural.ownedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só o dono edita o mural.");
		}
		mural.edit(validarTitulo(title), validarDescricao(description));
		return resumo(repository.saveMural(mural), repository.countContribuicoes(List.of(muralId))
			.getOrDefault(muralId, 0L));
	}

	@Transactional
	public void removeOwnMural(UserId userId, String muralId) {
		Mural mural = ativo(muralId);
		if (!mural.ownedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só o dono remove o mural.");
		}
		mural.remove(clock.instant());
		repository.saveMural(mural);
	}

	@Transactional
	public ContribuicaoView contribute(UserId userId, String muralId, String postId) {
		agePolicy.requireCanWrite(userId.value());
		sanctionGate.requireNotRestricted(userId.value());
		userRateLimiter.require(userId.value(), UserRateLimiter.CREATE_COMMENT);
		Mural mural = ativo(muralId);
		if (repository.findContribuicao(muralId, postId).isPresent()) {
			throw new ApiException(409, "JA_NO_MURAL", "Essa publicação já está neste mural.");
		}
		// A publicação precisa existir e ser visível para quem cola: sem isso, mural vira vitrine
		// de conteúdo privado. A visibilidade é a MESMA do feed (GetPostsUseCase).
		var visivel = posts.getPosts(List.of(postId), userId);
		if (visivel.isEmpty()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Publicação não encontrada ou invisível para você.");
		}
		MuralContribuicao contribuicao = MuralContribuicao.novo(muralId, postId, userId, clock.instant());
		repository.saveContribuicao(contribuicao);
		notificarAutorDoPost(mural, postId, userId);
		return new ContribuicaoView(postId, visivel.get(0), autor(userId), contribuicao.createdAt());
	}

	@Transactional
	public void removeContribution(UserId userId, String muralId, String postId) {
		Mural mural = ativo(muralId);
		MuralContribuicao contribuicao = repository.findContribuicao(muralId, postId)
			.orElseThrow(() -> new ApiException(404, "CONTRIBUICAO_NOT_FOUND", "Essa publicação não está no mural."));
		boolean colou = contribuicao.addedBy().equals(userId);
		if (!colou && !mural.ownedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Quem colou ou o dono do mural remove a publicação.");
		}
		repository.deleteContribuicao(contribuicao.id());
	}

	// ---- Leitura ----

	/**
	 * Os murais de quem pergunta, com a marca de ONDE este post já está colado — o seletor
	 * "adicionar ao mural" do card do feed usa isto: só os meus quadros, cada um dizendo se o
	 * post já está lá e quantos elos tem.
	 */
	/** O termo como vai para a consulta: sem espaços nas pontas, no máximo 120 e sem o vazio. */
	private static String termoDaBusca(String search) {
		return StringUtils.hasText(search) ? search.trim().substring(0, Math.min(search.trim().length(), MAX_BUSCA)) : "";
	}

	@Transactional(readOnly = true)
	public MuraisViews.MeusMuraisPage meus(UserId owner, String postId) {
		List<Mural> murais = repository.findActiveMuraisByOwner(owner);
		if (murais.isEmpty()) {
			return new MuraisViews.MeusMuraisPage(List.of());
		}
		Set<String> idsDosMurais = ids(murais);
		Map<String, Long> contagens = repository.countContribuicoes(idsDosMurais);
		Set<String> comOPost = Set.copyOf(repository.findMuralIdsComPost(postId, idsDosMurais));
		List<MuraisViews.MuralDoPost> items = murais.stream()
			.map(mural -> new MuraisViews.MuralDoPost(mural.id(), mural.title(), mural.description(),
				contagens.getOrDefault(mural.id(), 0L), comOPost.contains(mural.id()), mural.createdAt()))
			.toList();
		return new MuraisViews.MeusMuraisPage(items);
	}

	@Transactional(readOnly = true)
	public MuraisViews.MuraisPage list(UserId viewer, Optional<Instant> before, int limit) {
		return list(viewer, before, null, limit);
	}

	/**
	 * A lista do Explorar. Com `search`, só entram os quadros cujo título ou descrição têm o termo
	 * (sem diferenciar maiúsculas) — o espaço vazio é o filtro desligado, não "nada".
	 */
	@Transactional(readOnly = true)
	public MuraisViews.MuraisPage list(UserId viewer, Optional<Instant> before, String search, int limit) {
		int tamanho = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		Instant marca = before.orElseGet(() -> clock.instant().plusSeconds(1));
		List<Mural> murais = repository.findActiveMuraisBefore(marca, termoDaBusca(search), tamanho);
		Map<String, Long> contagens = repository.countContribuicoes(ids(murais));
		List<MuralResumo> items = murais.stream().map(mural -> resumo(mural, contagens.getOrDefault(mural.id(), 0L)))
			.toList();
		String nextCursor = murais.size() == tamanho ? cursorCodec.encode(murais.get(murais.size() - 1).createdAt())
			: null;
		return new MuraisViews.MuraisPage(items, nextCursor);
	}

	@Transactional(readOnly = true)
	public MuralResumo get(UserId viewer, String muralId) {
		Mural mural = ativo(muralId);
		return resumo(mural, repository.countContribuicoes(List.of(muralId)).getOrDefault(muralId, 0L));
	}

	@Transactional(readOnly = true)
	public MuraisViews.ContribuicoesPage contributions(UserId viewer, String muralId, Optional<Instant> before,
			int limit) {
		ativo(muralId);
		int tamanho = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		Instant marca = before.orElseGet(() -> clock.instant().plusSeconds(1));
		List<MuralContribuicao> contribuicoes = repository.findContribuicoesByMural(muralId, marca, tamanho);
		// As publicações vêm do feed com a MESMA visibilidade de sempre: post apagado, privado ou
		// bloqueado simplesmente não aparece no mural — o elo fica, a vitrine não.
		Map<String, PostView> visiveis = posts
			.getPosts(contribuicoes.stream().map(MuralContribuicao::postId).toList(), viewer).stream()
			.collect(Collectors.toMap(PostView::id, Function.identity()));
		Map<String, MuralAutor> coleiros = autorPorId(contribuicoes.stream()
			.map(c -> c.addedBy().value())
			.toList());
		List<ContribuicaoView> items = contribuicoes.stream()
			.filter(c -> visiveis.containsKey(c.postId()))
			.map(c -> new ContribuicaoView(c.postId(), visiveis.get(c.postId()), coleiros.get(c.addedBy().value()),
				c.createdAt()))
			.toList();
		String nextCursor = contribuicoes.size() == tamanho
			? cursorCodec.encode(contribuicoes.get(contribuicoes.size() - 1).createdAt())
			: null;
		return new MuraisViews.ContribuicoesPage(items, nextCursor);
	}

	// ---- Moderação (port MuraisModerationPort) ----

	@Override
	@Transactional
	public boolean removeMuralAsModerator(String actorId, String muralId, String reason) {
		return repository.findMuralById(muralId)
			.filter(Mural::isActive)
			.map(mural -> {
				mural.remove(clock.instant());
				repository.saveMural(mural);
				return true;
			})
			.orElse(false);
	}

	// ---- Internos ----

	private Mural ativo(String muralId) {
		return repository.findMuralById(muralId)
			.filter(Mural::isActive)
			.orElseThrow(() -> new ApiException(404, "MURAL_NOT_FOUND", "Mural não encontrado."));
	}

	private String validarTitulo(String title) {
		if (!StringUtils.hasText(title)) {
			throw new ApiException(400, "MURAL_TITLE_REQUIRED", "Dê um título ao mural.");
		}
		String titulo = title.trim();
		if (titulo.length() > MAX_TITULO) {
			throw new ApiException(400, "MURAL_TITLE_TOO_LONG", "O título aceita no máximo " + MAX_TITULO
				+ " caracteres.");
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
			throw new ApiException(400, "CONTENT_TOO_LONG", "A descrição aceita no máximo " + MAX_DESCRICAO
				+ " caracteres.");
		}
		return descricao;
	}

	private void notificarAutorDoPost(Mural mural, String postId, UserId quemColou) {
		var post = posts.getPosts(List.of(postId), null);
		if (post.isEmpty()) {
			return;
		}
		String authorId = post.get(0).authorId();
		if (authorId == null || authorId.equals(quemColou.value())) {
			return;
		}
		String nomeColeiro = profiles.findById(quemColou.value())
			.map(profile -> profile.displayWithNickname() != null ? profile.displayWithNickname() : profile.name())
			.orElse("Alguém");
		notifications.create(authorId, "MURAL_CONTRIBUTED", quemColou.value(), "Sua publicação entrou em um mural",
			nomeColeiro + " colou a sua publicação no mural \"" + mural.title() + "\".", "/murais/" + mural.id(),
			"Ver");
	}

	private MuralResumo resumo(Mural mural, long contribuicoes) {
		return new MuralResumo(mural.id(), mural.title(), mural.description(), autor(mural.ownerId()), contribuicoes,
			mural.createdAt());
	}

	private MuralAutor autor(UserId userId) {
		return autorPorId(List.of(userId.value())).getOrDefault(userId.value(),
			new MuralAutor(userId.value(), null, null, null, null, null));
	}

	private Map<String, MuralAutor> autorPorId(Collection<String> userIds) {
		Set<String> ids = Set.copyOf(userIds);
		return profiles.findAllByIds(ids).stream()
			.collect(Collectors.toMap(UserProfile::id, this::autorDoPerfil));
	}

	private MuralAutor autorDoPerfil(UserProfile profile) {
		return new MuralAutor(profile.id(), profile.name(), profile.username(), profile.avatar(),
			profile.avatarFrame(), NameStyleView.from(profile));
	}

	private Set<String> ids(List<Mural> murais) {
		return murais.stream().map(Mural::id).collect(Collectors.toSet());
	}
}

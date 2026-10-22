package com.socialcoisa.rede.games.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.games.application.port.out.GameRepository;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.games.domain.GameEventKind;
import com.socialcoisa.rede.games.domain.GameStatus;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Agregador de jogos: o Énóis NÃO hospeda — o criador envia o LINK (o jogo fica publicado
 * em itch.io/site próprio), a moderação revisa no mesmo quadro em que vai rodar, e o jogo
 * publicado roda EMBUTIDO com sandbox e tela cheia. Envio → revisão → publicação.
 */
@Service
public class GameService {

	private final GameRepository games;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles;
	private final GameUrlGuard urlGuard;
	private final com.socialcoisa.rede.games.application.port.out.GameUpdateRepository updates;
	private final com.socialcoisa.rede.games.application.port.out.DeveloperFollowRepository follows;
	private final com.socialcoisa.rede.notifications.application.NotificationService notifications;
	private final Clock clock;
	private final com.socialcoisa.rede.coin.application.CoinService coins;
	private final com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository ledger;
	private final com.socialcoisa.rede.games.application.port.out.GameCollectionRepository colecoes;
	private final com.socialcoisa.rede.games.application.port.out.GameEventRepository events;
	private final com.socialcoisa.rede.feed.application.PublishPostService feed;
	private final com.socialcoisa.rede.games.application.port.out.GameFavoriteRepository favorites;
	private final GameIdentityService identities;

	public GameService(GameRepository games,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository profiles, GameUrlGuard urlGuard,
			com.socialcoisa.rede.games.application.port.out.GameUpdateRepository updates,
			com.socialcoisa.rede.games.application.port.out.DeveloperFollowRepository follows,
			com.socialcoisa.rede.notifications.application.NotificationService notifications, Clock clock,
			com.socialcoisa.rede.coin.application.CoinService coins,
			com.socialcoisa.rede.coin.application.port.out.CoinLedgerRepository ledger,
			com.socialcoisa.rede.games.application.port.out.GameCollectionRepository colecoes,
			com.socialcoisa.rede.games.application.port.out.GameEventRepository events,
			com.socialcoisa.rede.feed.application.PublishPostService feed,
			com.socialcoisa.rede.games.application.port.out.GameFavoriteRepository favorites,
			GameIdentityService identities) {
		this.games = games;
		this.profiles = profiles;
		this.urlGuard = urlGuard;
		this.updates = updates;
		this.follows = follows;
		this.notifications = notifications;
		this.clock = clock;
		this.coins = coins;
		this.ledger = ledger;
		this.colecoes = colecoes;
		this.events = events;
		this.feed = feed;
		this.favorites = favorites;
		this.identities = identities;
	}

	@Transactional
	public Game submit(String userId, String slugBase, String title, String description, String cover, String url,
			String tags) {
		if (!StringUtils.hasText(title) || title.trim().length() < 3) {
			throw new ApiException(400, "INVALID_TITLE", "Título do jogo precisa de pelo menos 3 caracteres.");
		}
		String safeUrl = urlGuard.validate(url);
		String slug = uniqueSlug(userId, StringUtils.hasText(slugBase) ? slugFrom(slugBase) : slugFrom(title));
		Game game = Game.submit("game_" + UUID.randomUUID().toString().replace("-", ""), userId, slug, title.trim(),
			StringUtils.hasText(description) ? description.trim() : null,
			StringUtils.hasText(cover) ? cover.trim() : null, safeUrl,
			StringUtils.hasText(tags) ? tags.trim() : null, clock.instant());
		return games.save(game);
	}

	@Transactional
	public Game resubmit(String gameId, String userId) {
		Game game = game(gameId);
		if (!game.ownedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Só o criador pode reenviar.");
		}
		if (game.status() != GameStatus.REJECTED) {
			throw new ApiException(409, "NOT_REJECTED", "Só jogo recusado pode ser reenviado.");
		}
		return games.save(game.resubmit(clock.instant()));
	}

	/**
	 * Edição pelo dono. O endereço passa pelo mesmo guarda do envio, e a edição NÃO publica sozinha:
	 * quem já estava publicado continua publicado (corrigir a ficha não é passar pela fila de novo),
	 * e quem foi recusado volta para a fila pelo `resubmit`.
	 */
	@Transactional
	public Game edit(String gameId, String userId, String title, String description, String cover, String url,
			String tags, com.socialcoisa.rede.games.domain.GameDetails details) {
		return edit(gameId, userId, title, description, cover, url, tags, details, null);
	}

	/**
	 * Edição pelo dono, com o endereço público opcional: quem publica escolhe o slug na loja
	 * (`/games/{username}/{slug}`). Vazio mantém o atual; o formato e a colisão são validados aqui.
	 */
	@Transactional
	public Game edit(String gameId, String userId, String title, String description, String cover, String url,
			String tags, com.socialcoisa.rede.games.domain.GameDetails details, String slug) {
		Game game = owned(gameId, userId);
		if (!StringUtils.hasText(title) || title.trim().length() < 3) {
			throw new ApiException(400, "INVALID_TITLE", "Título do jogo precisa de pelo menos 3 caracteres.");
		}
		String safeUrl = urlGuard.validate(url);
		return games.save(game.edit(title.trim(),
			StringUtils.hasText(description) ? description.trim() : null,
			StringUtils.hasText(cover) ? cover.trim() : null, safeUrl,
			StringUtils.hasText(tags) ? tags.trim() : null, details, changeSlugIfNeeded(game, slug),
			clock.instant()));
	}

	/** Nulo = mantém o endereço; o slug novo é normalizado e não pode colidir com outro jogo do mesmo criador. */
	private String changeSlugIfNeeded(Game game, String requested) {
		if (!StringUtils.hasText(requested)) {
			return null;
		}
		String candidate = slugFrom(requested);
		if (candidate.equals(game.slug())) {
			return null;
		}
		if (candidate.length() < 3) {
			throw new ApiException(400, "INVALID_SLUG",
				"O endereço do jogo precisa de pelo menos 3 caracteres (letras, números ou hífen).");
		}
		if (games.existsByUserAndSlug(game.userId(), candidate)) {
			throw new ApiException(409, "SLUG_IN_USE", "Você já tem um jogo nesse endereço. Escolha outro.");
		}
		return candidate;
	}

	/**
	 * Excluir o jogo da loja (só o dono). O que existe POR CAUSA dele vai junto — novidades,
	 * favoritos, histórico de uso e tokens de identidade —, senão sobra lixo apontando para um jogo
	 * que não existe mais. As coleções guardam ids: o id que sobra simplesmente não resolve.
	 */
	@Transactional
	public void remove(String gameId, String userId) {
		owned(gameId, userId);
		updates.deleteByGame(gameId);
		favorites.deleteByGame(gameId);
		events.deleteByGame(gameId);
		identities.revokeForGame(gameId);
		games.delete(gameId);
	}

	/** Moderação tira o jogo do ar. O registro e o histórico continuam (suspender não é apagar). */
	@Transactional
	public Game suspend(String gameId, String reviewerId, String note) {
		Game game = game(gameId);
		if (game.status() == GameStatus.SUSPENDED) {
			throw new ApiException(409, "ALREADY_SUSPENDED", "Este jogo já está suspenso.");
		}
		return games.save(game.suspend(reviewerId, StringUtils.hasText(note) ? note.trim() : null, clock.instant()));
	}

	/** A moderação devolve o jogo ao ar depois da suspensão. */
	@Transactional
	public Game unsuspend(String gameId, String reviewerId) {
		Game game = game(gameId);
		if (game.status() != GameStatus.SUSPENDED) {
			throw new ApiException(409, "NOT_SUSPENDED", "Este jogo não está suspenso.");
		}
		return games.save(game.restoreFromSuspension(reviewerId, clock.instant()));
	}

	/** Nota do diário de desenvolvimento: o que mudou nesta versão. Avisa quem acompanha. */
	@Transactional
	public com.socialcoisa.rede.games.domain.GameUpdate publishUpdate(String gameId, String userId, String title,
			String body, String version) {
		Game game = owned(gameId, userId);
		if (!StringUtils.hasText(title)) {
			throw new ApiException(400, "INVALID_TITLE", "A novidade precisa de um título.");
		}
		com.socialcoisa.rede.games.domain.GameUpdate update = updates.save(
			com.socialcoisa.rede.games.domain.GameUpdate.create(
				"gup_" + UUID.randomUUID().toString().replace("-", ""), game.id(), userId, title.trim(),
				StringUtils.hasText(body) ? body.trim() : null,
				StringUtils.hasText(version) ? version.trim() : game.details().version(), clock.instant()));
		notifyFollowers(game, "Novidade em " + game.title(), update.title());
		return update;
	}

	@Transactional(readOnly = true)
	public java.util.List<com.socialcoisa.rede.games.domain.GameUpdate> updatesOf(String gameId, int limit) {
		return updates.findByGame(gameId, limit);
	}

	private Game owned(String gameId, String userId) {
		Game game = game(gameId);
		if (!game.ownedBy(userId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Este jogo não é seu.");
		}
		return game;
	}

	/** Quem acompanha o criador recebe um aviso — nunca o próprio criador. */
	private void notifyFollowers(Game game, String title, String body) {
		String author = profiles.findById(game.userId()).map(p -> p.name()).orElse("Um criador");
		String href = "/games/" + profiles.findById(game.userId()).map(p -> p.username()).orElse("") + "/"
			+ game.slug();
		for (String followerId : follows.followersOf(game.userId())) {
			if (followerId.equals(game.userId())) {
				continue;
			}
			notifications.create(followerId, "NEW_GAME", game.userId(), author + " · " + title, body, href, "Ver");
		}
	}

	/** Resolve o username da URL para o id do perfil (404 quando o perfil não existe). */
	@Transactional(readOnly = true)
	public String userIdFromUsername(String username) {
		return profiles.findByUsername(username)
			.map(com.socialcoisa.rede.profile.domain.UserProfile::id)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Criador não encontrado."));
	}

	public Game byUserAndSlug(String username, String slug) {
		return byUserAndSlug(username, slug, null);
	}

	/**
	 * Com o leitor: o dono vê o próprio jogo em qualquer estado; os outros, só publicado.
	 *
	 * A abertura PÚBLICA registra a visualização da página (é a série "visualizações" do painel).
	 * A do dono não conta: quem publica não infla os próprios números.
	 */
	@Transactional
	public Game byUserAndSlug(String username, String slug, String viewerId) {
		Game game = games.findByUserAndSlug(userIdFromUsername(username), slug)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado."));
		boolean owner = viewerId != null && game.ownedBy(viewerId);
		if (game.status() != GameStatus.PUBLISHED && !owner) {
			throw new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado.");
		}
		if (!owner) {
			events.record("gevt_" + UUID.randomUUID().toString().replace("-", ""), game.id(),
				GameEventKind.PAGE_VIEW, viewerId, clock.instant());
		}
		return game;
	}

	@Transactional(readOnly = true)
	public List<Game> publishedByUser(String username) {
		return games.findByUserAndStatus(userIdFromUsername(username), GameStatus.PUBLISHED, 50);
	}

	@Transactional(readOnly = true)
	public List<Game> ownedBy(String userId) {
		return games.findByUser(userId);
	}

	@Transactional(readOnly = true)
	public List<Game> catalog(int limit) {
		return games.findPublished(limit);
	}

	@Transactional(readOnly = true)
	public List<Game> pending(int limit) {
		return games.findByStatus(GameStatus.SUBMITTED, limit);
	}

	@Transactional
	public Game approve(String gameId, String reviewerId, String note) {
		Game game = game(gameId);
		if (game.status() != GameStatus.SUBMITTED) {
			throw new ApiException(409, "NOT_SUBMITTED", "Este jogo não está na fila de revisão.");
		}
		Game published = games.save(game.approve(reviewerId, StringUtils.hasText(note) ? note.trim() : null,
			clock.instant()));
		notifyFollowers(published, "Jogo publicado no Énóis", published.title());
		return published;
	}

	@Transactional
	public Game reject(String gameId, String reviewerId, String note) {
		Game game = game(gameId);
		if (game.status() != GameStatus.SUBMITTED) {
			throw new ApiException(409, "NOT_SUBMITTED", "Este jogo não está na fila de revisão.");
		}
		if (!StringUtils.hasText(note)) {
			throw new ApiException(400, "REVIEW_NOTE_REQUIRED", "A recusa precisa de um motivo.");
		}
		return games.save(game.reject(reviewerId, note.trim(), clock.instant()));
	}

	/**
	 * Conflito de interesse: quem modera nao aprova o proprio jogo. A fila de moderacao e aberta a
	 * moderadores da comunidade, e sem isso um moderador publicava o que ele mesmo enviou, pulando
	 * a revisao. Admin segue podendo (a rota /admin/games/... nao chama isto).
	 */
	public void requireNotOwnSubmission(String gameId, String reviewerId) {
		if (game(gameId).ownedBy(reviewerId)) {
			throw new ApiException(403, "OWN_SUBMISSION", "Quem modera não aprova o próprio jogo.");
		}
	}

	private Game game(String gameId) {
		return games.findById(gameId)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado."));
	}

	/**
	 * O endereço do jogo a partir do título. Acento vira letra (é → e) em vez de sumir: sem isso,
	 * "Café com Código" virava "caf-com-c-digo", que não é o título de ninguém.
	 */
	private String slugFrom(String value) {
		return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
	}

	private String uniqueSlug(String userId, String base) {
		String slug = StringUtils.hasText(base) ? base : "jogo";
		String candidate = slug;
		int n = 2;
		while (games.existsByUserAndSlug(userId, candidate)) {
			candidate = slug + "-" + n;
			n++;
		}
		return candidate;
	}


	/**
	 * GASTO DENTRO DO JOGO: o jogo PEDE (postMessage) e a pessoa CONFIRMA na plataforma — o
	 * débito sai daqui, com a sessão DELA. Idempotente por (jogo, requestId do jogo): um pedido
	 * repetido não cobra duas vezes. O valor volta para a tesouraria (supply fixo).
	 */
	@Transactional
	public void spendInGame(String userId, String gameId, Long amount, String motivo, String requestId) {
		com.socialcoisa.rede.games.domain.Game game = games.findById(gameId)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado."));
		if (game.status() != GameStatus.PUBLISHED) {
			throw new ApiException(409, "GAME_NOT_PUBLISHED", "Só jogo publicado cobra faíscas.");
		}
		if (amount == null || amount <= 0) {
			throw new ApiException(400, "INVALID_AMOUNT", "Valor da despesa obrigatório.");
		}
		if (!org.springframework.util.StringUtils.hasText(requestId)) {
			throw new ApiException(400, "REQUEST_ID_REQUIRED", "O pedido do jogo precisa de um id.");
		}
		String ref = "game:" + gameId + ":" + requestId.trim();
		coins.purchase(userId, amount, ref,
			"Gasto no jogo " + game.title() + (org.springframework.util.StringUtils.hasText(motivo)
				? " — " + motivo.trim() : ""));
		events.record("gev_" + java.util.UUID.randomUUID().toString().replace("-", ""), gameId,
			GameEventKind.SHARE, userId, java.time.Instant.now(clock));
	}

	/**
	 * Destaque pago (G2 do plano): o criador impulsiona o PRÓPRIO jogo — 5 faíscas por
	 * 7 dias, um impulso por semana por jogo. Sai do criador, entra na tesouraria
	 * (supply fixo preservado); a posse do carimbo é o lançamento do ledger.
	 */
	@Transactional
	public com.socialcoisa.rede.games.domain.Game boost(String gameId, String userId) {
		com.socialcoisa.rede.games.domain.Game game = games.findById(gameId)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado."));
		if (!game.ownedBy(userId)) {
			throw new ApiException(403, "NOT_THE_CREATOR", "Só o criador impulsiona o próprio jogo.");
		}
		if (game.status() != GameStatus.PUBLISHED) {
			throw new ApiException(409, "GAME_NOT_PUBLISHED", "Só jogo publicado pode ser impulsionado.");
		}
		java.time.Instant now = java.time.Instant.now(clock);
		var hoje = java.time.LocalDate.now(clock);
		var wf = java.time.temporal.WeekFields.ISO;
		String semana = wf.weekBasedYear().getFrom(hoje) + "-W" + wf.weekOfWeekBasedYear().getFrom(hoje);
		String ref = "boost:" + gameId + ":" + semana;
		if (ledger.existsByKindAndRefId(com.socialcoisa.rede.coin.domain.CoinEntry.Kind.PURCHASE, ref)) {
			throw new ApiException(409, "ALREADY_BOOSTED",
				"Este jogo já foi impulsionado nesta semana — volta no catálogo em sete dias.");
		}
		coins.purchase(userId, BOOST_PRICE_MF, ref, "Impulso do jogo: " + game.title());
		java.time.Instant base = game.boostUntil() != null && game.boostUntil().isAfter(now) ? game.boostUntil() : now;
		return games.save(game.boost(base.plus(java.time.Duration.ofDays(7)), now));
	}

	public static final long BOOST_PRICE_MF = 5_000L;


	// --- G3: coleções temáticas curadas (v1: administração) ---

	@Transactional
	public com.socialcoisa.rede.games.domain.GameCollection createCollection(String curatorId, String title,
			String description, List<String> gameIds) {
		return colecoes.save(
			com.socialcoisa.rede.games.domain.GameCollection.create(curatorId, title, description, gameIds,
				java.time.Instant.now(clock)));
	}

	@Transactional
	public com.socialcoisa.rede.games.domain.GameCollection hideCollection(String collectionId, String curatorId) {
		com.socialcoisa.rede.games.domain.GameCollection collection = colecoes.findById(collectionId)
			.orElseThrow(() -> new ApiException(404, "COLLECTION_NOT_FOUND", "Coleção não encontrada."));
		if (!collection.ownedBy(curatorId)) {
			throw new ApiException(403, "NOT_THE_CURATOR", "Só quem curou esconde a coleção.");
		}
		return colecoes.save(collection.hide(java.time.Instant.now(clock)));
	}

	/** As coleções publicadas com os jogos RESOLVIDOS (escondidos/suspensos saem da lista). */
	@Transactional(readOnly = true)
	public List<ColecaoView> collections(int limit) {
		return colecoes.findPublished(limit).stream().map(c -> new ColecaoView(c.id(), c.title(), c.description(),
			c.gameIds().stream()
				.map(id -> games.findById(id).orElse(null))
				.filter(g -> g != null && g.status() == GameStatus.PUBLISHED)
				.map(g -> GameView.of(g, null))
				.toList(),
			c.createdAt().toString())).toList();
	}

	public record ColecaoView(String id, String title, String description, List<GameView> jogos, String createdAt) {
	}


	/**
	 * G-share (compartilhar progresso): o jogo (iframe de outra origem, SEM sessão) manda o
	 * texto/mídia por postMessage; o app confirma com o jogador e publica AQUI, com a sessão
	 * dele — a sessão nunca vaza para o jogo e o clique humano é o que publica. Teto de 12
	 * compartilhamentos por dia por pessoa em cada jogo.
	 */
	@Transactional
	public com.socialcoisa.rede.feed.application.PostView compartilhar(String userId, String gameId, String texto,
			List<String> mediaIds) {
		com.socialcoisa.rede.games.domain.Game game = games.findById(gameId)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado."));
		if (game.status() != GameStatus.PUBLISHED) {
			throw new ApiException(409, "GAME_NOT_PUBLISHED", "Só jogo publicado compartilha progresso.");
		}
		if (!org.springframework.util.StringUtils.hasText(texto)) {
			throw new ApiException(400, "TEXT_REQUIRED", "A conquista precisa de um texto.");
		}
		String textoLimpo = texto.trim();
		if (textoLimpo.length() > 600) {
			throw new ApiException(400, "TEXT_TOO_LONG", "A conquista aceita no máximo 600 caracteres.");
		}
		java.time.Instant now = java.time.Instant.now(clock);
		java.time.Instant inicioDoDia = java.time.LocalDate.now(clock).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
		if (events.countByGameAndUserAndKindSince(gameId, GameEventKind.SHARE, userId, inicioDoDia) >= 12) {
			throw new ApiException(429, "SHARE_LIMIT",
				"Você já compartilhou 12 conquistas deste jogo hoje — amanhã tem mais.");
		}
		var post = feed.publish(new com.socialcoisa.rede.feed.application.PublishPostCommand(
			new com.socialcoisa.rede.feed.domain.UserId(userId), com.socialcoisa.rede.feed.domain.PostType.INSIGHT,
			textoLimpo, null, null, java.util.List.of("game", game.slug()),
			mediaIds == null ? List.of() : mediaIds, null, List.of()));
		events.record("gev_" + java.util.UUID.randomUUID().toString().replace("-", ""), gameId,
			GameEventKind.SHARE, userId, now);
		return post;
	}
}
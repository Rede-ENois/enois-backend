package com.socialcoisa.rede.games.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.games.application.port.out.GameEventRepository;
import com.socialcoisa.rede.games.application.port.out.GameFavoriteRepository;
import com.socialcoisa.rede.games.application.port.out.GameRepository;
import com.socialcoisa.rede.games.application.port.out.GameUpdateRepository;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.games.domain.GameEventKind;
import com.socialcoisa.rede.games.domain.GameStatus;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O catálogo: achar jogo, abrir jogo e contar o que aconteceu.
 *
 * Duas regras que valem a pena saber:
 *
 * - **só publicado aparece**. Rascunho, enviado, recusado e suspenso ficam fora do catálogo e da
 *   página pública — quem é dono vê o próprio em `/me/games`, e a moderação vê na fila.
 * - **quem conta é o servidor**. `plays` sobe quando alguém começa a jogar (o front avisa pelo
 *   endpoint, e o evento fica registrado). Visualização de página NÃO entra na conta: contador de
 *   "views" é o número mais fácil de inflar e o menos útil para decidir o que jogar.
 */
@Service
public class GameCatalogService {

	/** Teto de itens por página: acima disso é varredura, não navegação. */
	public static final int MAX_PAGE = 48;
	private static final int DEFAULT_PAGE = 24;

	private final GameRepository games;
	private final GameUpdateRepository updates;
	private final GameFavoriteRepository favorites;
	private final GameEventRepository events;
	private final TrendingGameTallies trending;
	private final Clock clock;

	public GameCatalogService(GameRepository games, GameUpdateRepository updates, GameFavoriteRepository favorites,
			GameEventRepository events, TrendingGameTallies trending, Clock clock) {
		this.games = games;
		this.updates = updates;
		this.favorites = favorites;
		this.events = events;
		this.trending = trending;
		this.clock = clock;
	}

	/** Uma página do catálogo + o cursor da próxima (nulo quando acabou). */
	public record Page(List<Game> items, String nextCursor) {
	}

	@Transactional(readOnly = true)
	public Page search(GameSearchCriteria criteria, Integer limit, String cursor) {
		int size = limit == null ? DEFAULT_PAGE : Math.max(1, Math.min(limit, MAX_PAGE));
		int offset = parseCursor(cursor);
		GameSearchPage found = games.search(criteria, size, offset);
		return new Page(found.items(), found.hasMore() ? String.valueOf(offset + size) : null);
	}

	/** A página do jogo: público só vê publicado; o dono vê o seu em qualquer estado. */
	@Transactional(readOnly = true)
	public Game open(String username, String slug, String viewerId) {
		Game game = games.findByUserAndSlug(username, slug)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado."));
		boolean owner = viewerId != null && game.ownedBy(viewerId);
		if (game.status() != GameStatus.PUBLISHED && !owner) {
			throw new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado.");
		}
		return game;
	}

	/**
	 * Alguém começou a jogar: conta a sessão e registra o evento.
	 *
	 * Não recebe "quantos jogaram" do front — o número é do servidor, e cada sessão é uma linha
	 * (jogador distinto é contado à parte, pelo id de quem está logado).
	 */
	@Transactional
	public int startPlay(String gameId, String viewerId) {
		Game game = games.findById(gameId)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado."));
		if (game.status() != GameStatus.PUBLISHED) {
			throw new ApiException(409, "GAME_NOT_PUBLISHED", "Este jogo não está publicado.");
		}
		events.record("gevt_" + UUID.randomUUID().toString().replace("-", ""), gameId, GameEventKind.PLAY_START,
			viewerId, clock.instant());
		return games.save(game.countPlay()).plays();
	}

	/** Números do jogo para a página: sessões, jogadores distintos estimados, favoritos e novidades. */
	@Transactional(readOnly = true)
	public Map<String, Object> stats(String gameId) {
		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("plays", games.findById(gameId).map(Game::plays).orElse(0));
		stats.put("players", events.distinctPlayers(gameId));
		stats.put("favorites", favorites.countByGame(gameId));
		stats.put("updates", updates.countByGame(gameId));
		return stats;
	}

	/** O que a pessoa acompanha: jogos publicados pelos criadores seguidos, mais recentes primeiro. */
	@Transactional(readOnly = true)
	public List<Game> fromFollowedDevelopers(List<String> developerIds, int limit) {
		return games.findPublishedByUsers(new ArrayList<>(developerIds), limit);
	}

	// --- "jogados hoje" ---

	/** Teto do ranking: mais que isso vira lista, e o card do feed é um punhado de jogos. */
	public static final int MAX_TRENDING = 12;

	/** Um jogo do ranking e o que ele fez na janela. */
	public record TrendingGame(Game game, long plays, long players) {
	}

	/**
	 * O ranking das últimas 24 horas, do mais jogado para o menos.
	 *
	 * A janela é rolante e não o dia de calendário de propósito: "hoje" depende do fuso de quem vê,
	 * e o servidor não conhece o fuso de ninguém. Vinte e quatro horas para trás dão o mesmo número
	 * para todo mundo e não zeram o card à meia-noite.
	 *
	 * Só publicado entra, como no resto do catálogo. Lêem-se o dobro de candidatos porque um jogo
	 * suspenso no topo não pode encolher o card.
	 */
	@Transactional(readOnly = true)
	public List<TrendingGame> trendingToday(int limit) {
		int size = Math.max(1, Math.min(limit, MAX_TRENDING));
		List<GameTally> tallies = trending.lastDay();
		Map<String, Game> published = new HashMap<>();
		games.findAllByIds(tallies.stream().map(GameTally::gameId).toList()).stream()
			.filter(game -> game.status() == GameStatus.PUBLISHED)
			.forEach(game -> published.put(game.id(), game));
		List<TrendingGame> ranked = new ArrayList<>();
		for (GameTally tally : tallies) {
			Game game = published.get(tally.gameId());
			if (game != null) {
				ranked.add(new TrendingGame(game, tally.plays(), tally.players()));
			}
			if (ranked.size() == size) {
				break;
			}
		}
		return ranked;
	}

	// --- painel do criador: gráfico de uso ---

	/** Teto de eventos lidos para a série: acima disso a leitura vira varredura caríssima. */
	private static final int MAX_SERIES_EVENTS = 50_000;

	/** Janela máxima do gráfico: um trimestre já é passado suficiente. */
	private static final int MAX_DAYS = 90;

	/**
	 * Um dia da série. TODOS os dias da janela aparecem, mesmo sem nenhum evento: o gráfico precisa
	 * do eixo fixo — dia vazio é informação (ninguém aparece), ausência de dia não é.
	 */
	public record DayUsage(String date, long pageViews, long plays, long shares, long players) {
	}

	/** Números do jogo no painel: totais + série diária. Só o criador vê os próprios números. */
	public record GameAnalytics(String from, String to, int days, long plays, long players, long favorites,
			long updates, List<DayUsage> series) {
	}

	@Transactional(readOnly = true)
	public GameAnalytics analytics(String gameId, String viewerId, Integer requestedDays) {
		Game game = games.findById(gameId)
			.orElseThrow(() -> new ApiException(404, "GAME_NOT_FOUND", "Jogo não encontrado."));
		if (viewerId == null || !game.ownedBy(viewerId)) {
			throw new ApiException(403, "NOT_AUTHORIZED", "Os números do jogo são só do criador.");
		}
		int days = requestedDays == null ? 30 : Math.clamp(requestedDays, 1, MAX_DAYS);
		java.time.LocalDate today = java.time.LocalDate.now(clock);
		java.time.LocalDate start = today.minusDays(days - 1L);
		java.time.Instant from = start.atStartOfDay(clock.getZone()).toInstant();

		Map<java.time.LocalDate, long[]> counters = new LinkedHashMap<>();
		Map<java.time.LocalDate, java.util.Set<String>> playersByDay = new LinkedHashMap<>();
		for (com.socialcoisa.rede.games.domain.GameEvent event : events.findSince(gameId, from, MAX_SERIES_EVENTS)) {
			java.time.LocalDate day = event.createdAt().atZone(clock.getZone()).toLocalDate();
			if (day.isBefore(start) || day.isAfter(today)) {
				continue;
			}
			long[] counter = counters.computeIfAbsent(day, key -> new long[3]);
			switch (event.kind()) {
				case PAGE_VIEW -> counter[0]++;
				// Jogador distinto POR DIA: quem joga em dois dias conta nos dois (é uso, não total).
				case PLAY_START -> {
					counter[1]++;
					playersByDay.computeIfAbsent(day, key -> new java.util.HashSet<>())
						.add(event.userId() == null ? event.id() : event.userId());
				}
				case SHARE -> counter[2]++;
			}
		}

		List<DayUsage> series = new ArrayList<>();
		for (java.time.LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
			long[] counter = counters.getOrDefault(day, new long[3]);
			series.add(new DayUsage(day.toString(), counter[0], counter[1], counter[2],
				playersByDay.getOrDefault(day, java.util.Set.of()).size()));
		}
		return new GameAnalytics(start.toString(), today.toString(), days,
			games.findById(gameId).map(Game::plays).orElse(0), events.distinctPlayers(gameId),
			favorites.countByGame(gameId), updates.countByGame(gameId), series);
	}

	private int parseCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return 0;
		}
		try {
			return Math.max(0, Integer.parseInt(cursor.trim()));
		}
		catch (NumberFormatException e) {
			return 0;
		}
	}
}

package com.socialcoisa.rede.games.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.games.application.GameSearchCriteria;
import com.socialcoisa.rede.games.application.GameSearchPage;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.games.domain.GameStatus;

public interface GameRepository {

	Game save(Game game);

	Optional<Game> findById(String id);

	/** The games that exist among `ids`, in one query (no order guaranteed). */
	List<Game> findAllByIds(List<String> ids);

	Optional<Game> findByUserAndSlug(String userId, String slug);

	List<Game> findByUser(String userId);

	List<Game> findByUserAndStatus(String userId, GameStatus status, int limit);

	List<Game> findByStatus(GameStatus status, int limit);

	/** Catálogo público: publicados, mais recentes primeiro. */
	List<Game> findPublished(int limit);

	boolean existsByUserAndSlug(String userId, String slug);

	/** Catálogo com busca/filtros/ordem e paginação por deslocamento (diz se há próxima página). */
	GameSearchPage search(GameSearchCriteria criteria, int limit, int offset);

	/** Quantos jogos publicados cada criador tem (chave = id do usuário). */
	Map<String, Long> publishedCountByUser();

	/** Jogos publicados de uma lista de criadores (a página de quem a pessoa acompanha). */
	List<Game> findPublishedByUsers(List<String> userIds, int limit);

	/** Remoção definitiva (o serviço já cuidou dos registros que dependem do jogo). */
	void delete(String id);
}

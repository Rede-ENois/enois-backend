package com.socialcoisa.rede.games.adapters.out.persistence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.games.application.GameSearchCriteria;
import com.socialcoisa.rede.games.application.GameSearchPage;
import com.socialcoisa.rede.games.application.port.out.GameRepository;
import com.socialcoisa.rede.games.domain.Game;
import com.socialcoisa.rede.games.domain.GameStatus;

@Repository
class GamePersistenceAdapter implements GameRepository {

	private final SpringDataGameJpaRepository games;

	GamePersistenceAdapter(SpringDataGameJpaRepository games) {
		this.games = games;
	}

	@Override
	public Game save(Game game) {
		return games.save(GameJpaEntity.fromDomain(game)).toDomain();
	}

	@Override
	public Optional<Game> findById(String id) {
		return games.findById(id).map(GameJpaEntity::toDomain);
	}

	@Override
	public List<Game> findAllByIds(List<String> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return games.findAllById(ids).stream().map(GameJpaEntity::toDomain).toList();
	}

	@Override
	public Optional<Game> findByUserAndSlug(String userId, String slug) {
		return games.findByUserIdAndSlug(userId, slug).map(GameJpaEntity::toDomain);
	}

	@Override
	public List<Game> findByUser(String userId) {
		return games.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(GameJpaEntity::toDomain).toList();
	}

	@Override
	public List<Game> findByUserAndStatus(String userId, GameStatus status, int limit) {
		return games.findByUserIdAndStatusOrderBySubmittedAtDesc(userId, status).stream()
			.limit(Math.max(1, limit)).map(GameJpaEntity::toDomain).toList();
	}

	@Override
	public List<Game> findByStatus(GameStatus status, int limit) {
		return games.findByStatusOrderBySubmittedAtDesc(status, PageRequest.of(0, Math.max(1, limit))).stream()
			.map(GameJpaEntity::toDomain).toList();
	}

	@Override
	public List<Game> findPublished(int limit) {
		return findByStatus(GameStatus.PUBLISHED, limit);
	}

	@Override
	public boolean existsByUserAndSlug(String userId, String slug) {
		return games.existsByUserIdAndSlug(userId, slug);
	}

	@Override
	public GameSearchPage search(GameSearchCriteria criteria, int limit, int offset) {
		// `Slice` é o que responde "tem mais?" sem uma segunda consulta de contagem: ele busca um a
		// mais por conta própria. A página sai do tamanho REAL e do deslocamento — somar o "+1" aqui
		// deslocava a janela e a segunda página voltava vazia.
		int size = Math.max(1, limit);
		Slice<GameJpaEntity> slice = games.search(GameStatus.PUBLISHED, criteria.queryPattern(),
			criteria.genrePattern(), criteria.tagPattern(), criteria.stage(), criteria.distribution(),
			PageRequest.of(Math.max(0, offset) / size, size, sortFor(criteria.order())));
		return new GameSearchPage(slice.getContent().stream().map(GameJpaEntity::toDomain).toList(),
			slice.hasNext());
	}

	/** A ordem é lista fechada: a coluna nunca vem da URL. */
	private Sort sortFor(String order) {
		// Destaque pago primeiro (G2): boostUntil no futuro sobe; nulls ficam por último no DESC.
		Sort destaque = Sort.by(Sort.Direction.DESC, "boostUntil");
		return switch (order) {
			case "plays" -> destaque.and(Sort.by(Sort.Direction.DESC, "plays"))
				.and(Sort.by(Sort.Direction.DESC, "publishedAt"));
			case "title" -> destaque.and(Sort.by(Sort.Direction.ASC, "title"));
			default -> destaque.and(Sort.by(Sort.Direction.DESC, "publishedAt"))
				.and(Sort.by(Sort.Direction.DESC, "plays"));
		};
	}

	@Override
	public Map<String, Long> publishedCountByUser() {
		Map<String, Long> counts = new HashMap<>();
		for (Object[] row : games.countByUser(GameStatus.PUBLISHED)) {
			counts.put((String) row[0], ((Number) row[1]).longValue());
		}
		return counts;
	}

	@Override
	public List<Game> findPublishedByUsers(List<String> userIds, int limit) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		return games.findByUserIdInAndStatusOrderByPublishedAtDesc(userIds, GameStatus.PUBLISHED,
			PageRequest.of(0, Math.max(1, limit))).stream().map(GameJpaEntity::toDomain).toList();
	}

	@Override
	public void delete(String id) {
		games.deleteById(id);
	}
}

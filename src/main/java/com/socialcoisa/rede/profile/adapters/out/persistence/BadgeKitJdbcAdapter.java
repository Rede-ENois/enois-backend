package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.profile.application.port.out.BadgeKitRepository;
import com.socialcoisa.rede.profile.domain.BadgeKit;

/**
 * Histórico de kits de emblemas, em SQL direto (mesmo estilo do adapter da fila de liberação).
 *
 * <p>O formato das entradas é o mesmo de {@code users.badges}: uma URL ou id por linha, na ordem
 * em que eram exibidas. A poda para 3 acontece no próprio INSERT/DELETE — não há worker.</p>
 */
@Component
class BadgeKitJdbcAdapter implements BadgeKitRepository {

	/** Quantos kits ficam no histórico por pessoa (spec do produto). */
	static final int HISTORY_LIMIT = 3;

	private static final String SELECT = """
		select id, badges, created_at from badge_kits
		where user_id = ?
		order by created_at desc
		limit ?
		""";

	private final JdbcTemplate jdbc;

	BadgeKitJdbcAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public void record(String userId, BadgeKit kit) {
		jdbc.update("insert into badge_kits (id, user_id, badges, created_at) values (?, ?, ?, ?)",
			kit.id(), userId, String.join("\n", kit.badges()), Timestamp.from(kit.createdAt()));
		jdbc.update("""
			delete from badge_kits
			where user_id = ? and id not in (
				select id from (
					select id from badge_kits where user_id = ? order by created_at desc limit ?
				) keep
			)
			""", userId, userId, HISTORY_LIMIT);
	}

	@Override
	public List<BadgeKit> history(String userId) {
		return jdbc.query(SELECT, (rs, row) -> kit(userId, rs), userId, HISTORY_LIMIT);
	}

	@Override
	public Optional<BadgeKit> find(String userId, String kitId) {
		List<BadgeKit> found = jdbc.query("""
			select id, badges, created_at from badge_kits where user_id = ? and id = ?
			""", (rs, row) -> kit(userId, rs), userId, kitId);
		return found.stream().findFirst();
	}

	@Override
	public void delete(String userId, String kitId) {
		jdbc.update("delete from badge_kits where user_id = ? and id = ?", userId, kitId);
	}

	private static BadgeKit kit(String userId, java.sql.ResultSet rs) throws java.sql.SQLException {
		Timestamp createdAt = rs.getTimestamp("created_at");
		return new BadgeKit(rs.getString("id"),
			List.of(rs.getString("badges").split("\n")),
			createdAt == null ? Instant.EPOCH : createdAt.toInstant());
	}
}

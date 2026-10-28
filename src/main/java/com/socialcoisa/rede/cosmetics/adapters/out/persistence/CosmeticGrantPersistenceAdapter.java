package com.socialcoisa.rede.cosmetics.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.cosmetics.application.port.out.CosmeticGrantRepository;

@Repository
class CosmeticGrantPersistenceAdapter implements CosmeticGrantRepository {

	private final JdbcTemplate jdbc;

	CosmeticGrantPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<String> itemIdsOf(String userId) {
		return jdbc.queryForList("select item_id from cosmetic_grants where user_id = ?", String.class, userId);
	}

	@Override
	public boolean exists(String userId, String itemId) {
		Integer count = jdbc.queryForObject("select count(*) from cosmetic_grants where user_id = ? and item_id = ?",
			Integer.class, userId, itemId);
		return count != null && count > 0;
	}

	@Override
	public void grant(String userId, String itemId, String source, Instant now) {
		try {
			jdbc.update("insert into cosmetic_grants (user_id, item_id, source, created_at) values (?, ?, ?, ?)",
				userId, itemId, source, Timestamp.from(now));
		}
		catch (DuplicateKeyException alreadyGranted) {
			// The primary key is the idempotency rule: a concurrent or repeated grant keeps the first row.
		}
	}

}

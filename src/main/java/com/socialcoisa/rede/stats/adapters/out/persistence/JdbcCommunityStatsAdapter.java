package com.socialcoisa.rede.stats.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.stats.application.port.out.CommunityStatsReader;

/**
 * Contagem de usuarios ativos direto no banco, com uma consulta so.
 *
 * A definicao vive no SQL de proposito: sessao "viva" e a que tem refresh valido (o refresh de 30
 * dias e renovado a cada acesso, entao ele e o sinal mais fiel de "usou nos ultimos 30 dias"),
 * somada a quem publicou post ou comentario no periodo. O indice de sessao (user_id, expires_at),
 * o de posts (created_at) e o de comentarios (post_id, created_at) sustentam a consulta; mesmo
 * assim ela nao e barata, e por isso o resultado passa pelo cache do servico.
 *
 * `union` (e nao `union all` + count) para nao contar a mesma pessoa duas vezes quando ela tem
 * sessao e conteudo no periodo.
 */
@Repository
class JdbcCommunityStatsAdapter implements CommunityStatsReader {

	private static final String ACTIVE_USERS_SQL = """
		select count(*) from (
		  select user_id from auth_sessions
		    where revoked_at is null and refresh_expires_at > ?
		  union
		  select author_id from posts where created_at > ? and status not in ('DELETED', 'QUARANTINED')
		  union
		  select author_id from comments where created_at > ?
		) as ativos
		""";

	private final JdbcTemplate jdbcTemplate;

	JdbcCommunityStatsAdapter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public long countActiveSince(Instant since) {
		Timestamp reference = Timestamp.from(since);
		Long count = jdbcTemplate.queryForObject(ACTIVE_USERS_SQL, Long.class, reference, reference, reference);
		return count == null ? 0L : count;
	}

	@Override
	public long countUsers() {
		Long count = jdbcTemplate.queryForObject("select count(*) from users", Long.class);
		return count == null ? 0L : count;
	}
}

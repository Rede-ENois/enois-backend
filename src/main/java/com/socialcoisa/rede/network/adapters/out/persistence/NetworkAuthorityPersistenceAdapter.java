package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;

/**
 * Termo da autoridade e eleitorado declarado (V303), em JDBC direto.
 *
 * <p>JDBC e não JPA pelo mesmo motivo da versão de conteúdo: a promoção é condicional por natureza.
 * Ler o termo, comparar na aplicação e gravar teria a janela em que duas instâncias leem o mesmo
 * termo e as duas se promovem. Aqui é uma instrução com {@code where term = ?} e o banco decide
 * quem ganhou — quem perder atualiza zero linhas.</p>
 */
@Repository
class NetworkAuthorityPersistenceAdapter implements NetworkAuthorityRepository {

	private static final String SELECT_STATE = """
		select term, authority_id, promoted_at from network_state where id = 'global'
		""";

	/**
	 * Compare-and-set do termo. O modo não é tocado: promoção troca de autoridade, não de modo
	 * operacional, e um {@code EMERGENCY} declarado continua valendo depois da promoção.
	 */
	private static final String PROMOTE = """
		update network_state set term = ?, authority_id = ?, promoted_at = ?
		where id = 'global' and term = ?
		""";

	private static final String SELECT_DECLARED = """
		select instance_id from network_authorities order by declared_at asc, instance_id asc
		""";

	/** Repetir a declaração é no-op: `declared_at` é quando aquela instância ENTROU no eleitorado. */
	private static final String DECLARE = """
		insert into network_authorities (instance_id, declared_at, declared_by)
		values (?, ?, ?)
		on duplicate key update instance_id = instance_id
		""";

	private static final String REVOKE = """
		delete from network_authorities where instance_id = ?
		""";

	private static final String ALIVE = """
		select r.instance_id
		from network_instance_readers r
		join network_authorities a on a.instance_id = r.instance_id
		where r.last_seen_at >= ?
		""";

	private final JdbcTemplate jdbc;

	NetworkAuthorityPersistenceAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<AuthorityRecord> current() {
		return jdbc.query(SELECT_STATE, (rs, row) -> new AuthorityRecord(rs.getInt("term"),
			rs.getString("authority_id"), rs.getTimestamp("promoted_at") == null ? null
					: rs.getTimestamp("promoted_at").toInstant()))
			.stream()
			.findFirst();
	}

	@Override
	public boolean promote(String authorityId, int expectedTerm, int nextTerm, Instant now) {
		return jdbc.update(PROMOTE, nextTerm, authorityId, Timestamp.from(now), expectedTerm) == 1;
	}

	@Override
	public List<String> declared() {
		return jdbc.query(SELECT_DECLARED, (rs, row) -> rs.getString("instance_id"));
	}

	@Override
	public void declare(String instanceId, String actorId, Instant now) {
		jdbc.update(DECLARE, instanceId, Timestamp.from(now), actorId);
	}

	@Override
	public boolean revoke(String instanceId) {
		return jdbc.update(REVOKE, instanceId) > 0;
	}

	@Override
	public Set<String> aliveSince(Instant threshold) {
		return Set.copyOf(jdbc.query(ALIVE, (rs, row) -> rs.getString("instance_id"), Timestamp.from(threshold)));
	}
}
package com.socialcoisa.rede.network.adapters.in.web;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.shared.web.TestAuthHelper;

/** Posiciona somente as contas do cenário depois da âncora, mesmo com fixtures futuras no H2. */
final class PerfisReplicaFixture implements AutoCloseable {

	private final TestAuthHelper delegate;

	private final JdbcTemplate jdbc;

	private final Map<String, Timestamp> datasOriginais = new LinkedHashMap<>();

	PerfisReplicaFixture(TestAuthHelper delegate, JdbcTemplate jdbc) {
		this.delegate = delegate;
		this.jdbc = jdbc;
	}

	TestAuthHelper.AuthSession authenticate(String nome) throws Exception {
		var session = delegate.authenticate(nome);
		Timestamp original = jdbc.queryForObject("select created_at from users where id = ?", Timestamp.class,
				session.userId());
		datasOriginais.putIfAbsent(session.userId(), original);
		Timestamp ultima = jdbc.queryForObject("select max(created_at) from users", Timestamp.class);
		jdbc.update("update users set created_at = ? where id = ?", ultima.toInstant().plusSeconds(1),
				session.userId());
		return session;
	}

	@Override
	public void close() {
		datasOriginais.forEach((id, data) -> jdbc.update("update users set created_at = ? where id = ?", data, id));
		datasOriginais.clear();
	}
}

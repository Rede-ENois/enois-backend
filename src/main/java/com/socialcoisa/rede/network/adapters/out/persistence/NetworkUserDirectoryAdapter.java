package com.socialcoisa.rede.network.adapters.out.persistence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.network.application.port.out.NetworkUserDirectory;

/** Nome e e-mail da conta dona, para o painel mostrar o responsável em vez de um id solto. */
@Repository
class NetworkUserDirectoryAdapter implements NetworkUserDirectory {

	private final JdbcTemplate jdbc;

	NetworkUserDirectoryAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Account> byId(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		return jdbc.query("select id, name, email from users where id = ?",
			(rs, row) -> new Account(rs.getString("id"), rs.getString("name"), rs.getString("email")), id)
			.stream().findFirst();
	}

	@Override
	public Optional<Account> byEmail(String email) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		// Comparação sem diferenciar caixa: o cadastro do site não promete guardar o e-mail no
		// mesmo formato em que o formulário de inscrição o digitou, e a mesma pessoa não pode virar
		// duas contas por causa de uma maiúscula.
		return jdbc.query("select id, name, email from users where lower(email) = lower(?)",
			(rs, row) -> new Account(rs.getString("id"), rs.getString("name"), rs.getString("email")), email.trim())
			.stream().findFirst();
	}

	@Override
	public Map<String, Account> byIds(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
		Map<String, Account> found = new HashMap<>();
		jdbc.query("select id, name, email from users where id in (" + placeholders + ")", rs -> {
			Account account = new Account(rs.getString("id"), rs.getString("name"), rs.getString("email"));
			found.put(account.id(), account);
		}, ids.toArray());
		return found;
	}
}
package com.socialcoisa.rede.wrapped.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.wrapped.application.port.out.WrappedComunidadesRepository;

/**
 * As comunidades onde a conta esteve no ano: entrou como membro no ano e/ou publicou no ano.
 * Uma consulta só, agregada no banco — a contagem de publicações por comunidade vem de {@code posts}.
 */
@Repository
class WrappedComunidadesJdbcAdapter implements WrappedComunidadesRepository {

	private final JdbcTemplate jdbc;

	WrappedComunidadesJdbcAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<Participacao> daConta(String userId, Instant de, Instant ate) {
		Map<String, Membro> porComunidade = new LinkedHashMap<>();
		for (var linha : jdbc.queryForList("select c.id, c.name from community_members m"
				+ " join communities c on c.id = m.community_id"
				+ " where m.user_id = ? and m.joined_at >= ? and m.joined_at < ?", userId, de, ate)) {
			String id = (String) linha.get("id");
			porComunidade.computeIfAbsent(id, k -> new Membro(id, (String) linha.get("name"))).membro = true;
		}
		for (var linha : jdbc.queryForList("select p.community_id, c.name, count(*) as total from posts p"
				+ " join communities c on c.id = p.community_id"
				+ " where p.author_id = ? and p.created_at >= ? and p.created_at < ? and p.status = 'ACTIVE'"
				+ " and p.deleted_at is null and p.community_id is not null"
				+ " group by p.community_id, c.name", userId, de, ate)) {
			String id = (String) linha.get("community_id");
			porComunidade.computeIfAbsent(id, k -> new Membro(id, (String) linha.get("name"))).publicacoes =
					((Number) linha.get("total")).intValue();
		}

		List<Participacao> resultado = new ArrayList<>();
		for (Membro m : porComunidade.values()) {
			resultado.add(new Participacao(m.id, m.nome, m.membro, m.publicacoes));
		}
		return resultado;
	}

	private static final class Membro {
		private final String id;
		private final String nome;
		private boolean membro;
		private int publicacoes;

		private Membro(String id, String nome) {
			this.id = id;
			this.nome = nome;
		}
	}
}

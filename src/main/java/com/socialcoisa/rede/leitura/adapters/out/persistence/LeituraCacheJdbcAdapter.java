package com.socialcoisa.rede.leitura.adapters.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.leitura.application.port.out.LeituraCache;
import com.socialcoisa.rede.leitura.domain.Intencao;
import com.socialcoisa.rede.leitura.domain.LeituraTexto;

/**
 * O cache de leitura em {@code leitura_texto}. Temas guardados como {@code a|b|c}; intenção no
 * nome do enum; procedência em {@code lido_pelo_modelo}. Salvar sobrescreve a mesma chave.
 */
@Repository
class LeituraCacheJdbcAdapter implements LeituraCache {

	private static final String SEPARADOR = "|";

	private final JdbcTemplate jdbc;

	LeituraCacheJdbcAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<String, LeituraTexto> porHashes(Collection<String> hashes) {
		if (hashes.isEmpty()) {
			return Map.of();
		}
		Map<String, LeituraTexto> resultado = new HashMap<>();
		List<String> lista = new ArrayList<>(hashes);
		// Em blocos, porque o IN do banco tem limite de parâmetros.
		for (int inicio = 0; inicio < lista.size(); inicio += 500) {
			List<String> bloco = lista.subList(inicio, Math.min(lista.size(), inicio + 500));
			String marcadores = String.join(",", java.util.Collections.nCopies(bloco.size(), "?"));
			jdbc.query("select conteudo_hash, temas, intencao, resumo, lido_pelo_modelo from leitura_texto"
					+ " where conteudo_hash in (" + marcadores + ")", rs -> {
						List<String> temas = rs.getString("temas") == null || rs.getString("temas").isBlank()
								? List.of()
								: List.of(rs.getString("temas").split("\\|"));
						resultado.put(rs.getString("conteudo_hash"), new LeituraTexto(temas,
								Intencao.parse(rs.getString("intencao")), rs.getString("resumo"),
								rs.getBoolean("lido_pelo_modelo")));
					}, bloco.toArray());
		}
		return resultado;
	}

	@Override
	public void salvar(String hash, LeituraTexto leitura, int versao) {
		String temas = String.join(SEPARADOR, leitura.temas());
		java.sql.Timestamp agora = java.sql.Timestamp.from(Instant.now());
		// Update primeiro, insert depois: portável no H2 e no MariaDB (o upsert de cada banco difere).
		int alteradas = jdbc.update("update leitura_texto set temas = ?, intencao = ?, resumo = ?,"
				+ " lido_pelo_modelo = ?, versao = ?, lido_em = ? where conteudo_hash = ?",
				temas, leitura.intencao().name(), leitura.resumo(), leitura.lidoPeloModelo(), versao, agora, hash);
		if (alteradas == 0) {
			jdbc.update("insert into leitura_texto (conteudo_hash, temas, intencao, resumo, lido_pelo_modelo,"
					+ " versao, lido_em) values (?,?,?,?,?,?,?)",
					hash, temas, leitura.intencao().name(), leitura.resumo(), leitura.lidoPeloModelo(), versao, agora);
		}
	}

	@Override
	public long degradadas() {
		Long total = jdbc.queryForObject("select count(*) from leitura_texto where lido_pelo_modelo = false",
				Long.class);
		return total == null ? 0 : total;
	}
}

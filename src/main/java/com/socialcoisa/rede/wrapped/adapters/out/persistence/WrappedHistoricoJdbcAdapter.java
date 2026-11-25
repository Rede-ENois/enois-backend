package com.socialcoisa.rede.wrapped.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.wrapped.application.port.out.WrappedEvento;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedEvento.Tipo;
import com.socialcoisa.rede.wrapped.application.port.out.WrappedHistoricoRepository;

/**
 * A leitura paginada do histórico de uma conta no ano, num único SQL.
 *
 * <p>Cada fonte é uma subconsulta união, filtrada por ator e intervalo e por status ativo (post
 * ACTIVE e não apagado; comentário ACTIVE). O "ator" é sempre a própria conta: como autora em
 * posts/comentários, e como {@code user_id} em reações/reposts/compartilhamentos/salvos. As
 * interações RECEBIDAS vêm pelo autor da publicação. O texto só é lido para posts e comentários
 * da conta (o que é público). O cursor é a chave {@code fato} (estável e única), e a próxima
 * página pede as chaves maiores — nunca carrega o ano inteiro de uma vez.</p>
 */
@Repository
class WrappedHistoricoJdbcAdapter implements WrappedHistoricoRepository {

	private final NamedParameterJdbcTemplate jdbc;

	WrappedHistoricoJdbcAdapter(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Uma fatia temporal por fonte: a subconsulta já filtra o ator, o intervalo e o status. A
	 * query externa devolve as colunas comuns de {@link WrappedEvento}.
	 */
	private static final String FATOR = """
			select 'posts' as fonte, p.id as fato, p.id as post_id, p.created_at as momento,
			       p.content as texto, p.author_id as autor_id, u.username as autor_username,
			       u.name as autor_nome, p.community_id as community_id
			from posts p left join users u on u.id = p.author_id
			where p.author_id = :user and p.created_at >= :de and p.created_at < :ate
			  and p.status = 'ACTIVE' and p.deleted_at is null
			union all
			select 'comments', c.id, c.post_id, c.created_at, c.content, c.author_id, u.username, u.name,
			       p.community_id
			from comments c
			join posts p on p.id = c.post_id
			left join users u on u.id = c.author_id
			where c.author_id = :user and c.created_at >= :de and c.created_at < :ate
			  and c.status = 'ACTIVE' and p.status = 'ACTIVE' and p.deleted_at is null
			union all
			select 'reactions', r.id, r.post_id, r.created_at, null, r.user_id, u.username, u.name, p.community_id
			from reactions r join posts p on p.id = r.post_id left join users u on u.id = r.user_id
			where r.user_id = :user and r.created_at >= :de and r.created_at < :ate
			  and p.status = 'ACTIVE' and p.deleted_at is null
			union all
			select 'reposts', cast(rp.id as char), rp.post_id, rp.created_at, null, rp.user_id, u.username, u.name,
			       p.community_id
			from post_reposts rp join posts p on p.id = rp.post_id left join users u on u.id = rp.user_id
			where rp.user_id = :user and rp.created_at >= :de and rp.created_at < :ate
			  and p.status = 'ACTIVE' and p.deleted_at is null
			union all
			select 'shares', s.id, s.post_id, s.created_at, null, s.user_id, u.username, u.name, p.community_id
			from post_shares s join posts p on p.id = s.post_id left join users u on u.id = s.user_id
			where s.user_id = :user and s.created_at >= :de and s.created_at < :ate
			  and p.status = 'ACTIVE' and p.deleted_at is null
			union all
			select 'saves', sp.id, sp.post_id, sp.created_at, null, sp.user_id, u.username, u.name, p.community_id
			from saved_posts sp join posts p on p.id = sp.post_id left join users u on u.id = sp.user_id
			where sp.user_id = :user and sp.created_at >= :de and sp.created_at < :ate
			  and p.status = 'ACTIVE' and p.deleted_at is null
			union all
			select 'poll_votes', pv.id, pv.post_id, pv.created_at, null, pv.user_id, u.username, u.name, null
			from poll_votes pv left join users u on u.id = pv.user_id
			where pv.user_id = :user and pv.created_at >= :de and pv.created_at < :ate
			union all
			select 'reacted', cr.id, c.post_id, cr.created_at, null, c.author_id, u.username, u.name, null
			from comment_reactions cr join comments c on c.id = cr.comment_id
			left join users u on u.id = c.author_id
			where c.author_id = :user and cr.user_id <> :user and cr.created_at >= :de and cr.created_at < :ate
			  and c.status = 'ACTIVE'
			union all
			select 'commented', c.id, c.post_id, c.created_at, c.content, c.author_id, u.username, u.name,
			       p.community_id
			from comments c join posts p on p.id = c.post_id left join users u on u.id = c.author_id
			where p.author_id = :user and c.author_id <> :user and c.created_at >= :de and c.created_at < :ate
			  and c.status = 'ACTIVE' and p.status = 'ACTIVE' and p.deleted_at is null
			union all
			select 'shared', s.id, s.post_id, s.created_at, null, s.user_id, u.username, u.name, p.community_id
			from post_shares s join posts p on p.id = s.post_id left join users u on u.id = s.user_id
			where p.author_id = :user and s.user_id <> :user and s.created_at >= :de and s.created_at < :ate
			  and p.status = 'ACTIVE' and p.deleted_at is null
			union all
			select 'reposted', cast(rp.id as char), rp.post_id, rp.created_at, null, rp.user_id, u.username, u.name,
			       p.community_id
			from post_reposts rp join posts p on p.id = rp.post_id left join users u on u.id = rp.user_id
			where p.author_id = :user and rp.user_id <> :user and rp.created_at >= :de and rp.created_at < :ate
			  and p.status = 'ACTIVE' and p.deleted_at is null
			""";

	@Override
	public List<WrappedEvento> pagina(String userId, Instant de, Instant ate, String depoisDeCursor, int limite) {
		String sql = "select * from (" + FATOR + ") t"
				+ (depoisDeCursor == null ? "" : " where t.fato > :cursor")
				+ " order by t.fato limit :limite";
		return jdbc.query(sql, new MapSqlParameterSource()
						.addValue("user", userId)
						.addValue("de", Timestamp.from(de))
						.addValue("ate", Timestamp.from(ate))
						.addValue("cursor", depoisDeCursor)
						.addValue("limite", limite),
				(rs, n) -> new WrappedEvento(
						rs.getString("fato"),
						instante(rs.getTimestamp("momento")),
						tipo(rs.getString("fonte")),
						rs.getString("post_id"),
						rs.getString("texto"),
						rs.getString("autor_id"),
						rs.getString("autor_username"),
						rs.getString("autor_nome"),
						rs.getString("community_id")));
	}

	private static Instant instante(Timestamp valor) {
		return valor == null ? null : valor.toInstant();
	}

	/** A fonte SQL → o tipo de evento que o agregador conhece. */
	private static Tipo tipo(String fonte) {
		return switch (fonte) {
			case "posts" -> Tipo.PUBLICACAO;
			case "comments" -> Tipo.COMENTARIO_PROPRIO;
			case "reactions", "poll_votes" -> Tipo.REACAO_FEITA;
			case "reposts" -> Tipo.REPOST_FEITO;
			case "shares", "saves" -> Tipo.COMPARTILHAMENTO_FEITO;
			case "reacted" -> Tipo.REACAO_RECEBIDA;
			case "commented" -> Tipo.COMENTARIO_RECEBIDO;
			case "shared" -> Tipo.COMPARTILHAMENTO_RECEBIDO;
			case "reposted" -> Tipo.REPOST_RECEBIDO;
			default -> throw new IllegalStateException("Fonte desconhecida: " + fonte);
		};
	}
}

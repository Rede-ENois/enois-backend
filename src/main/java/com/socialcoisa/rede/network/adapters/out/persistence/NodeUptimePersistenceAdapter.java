package com.socialcoisa.rede.network.adapters.out.persistence;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.network.application.port.out.NodeUptimeRepository;
import com.socialcoisa.rede.network.domain.NodeAvailability;

/**
 * O contador diário em JDBC direto: a escrita é uma soma condicional no {@code upsert}, e um
 * {@code save()} esconderia justamente a intenção — "acrescente", não "substitua".
 *
 * <h2>Quem decide se o trecho entra</h2>
 *
 * <p>A regra é de negócio e mora no serviço: só entra trecho que termina <b>depois</b> do último
 * contato já contado do dia. Por isso a assinatura recebe {@code jaContado} — o valor que o serviço
 * leu —, e o {@code where} deste adaptador reconfere o mesmo predicado na escrita. A reconferência
 * não é a decisão (o serviço já decidiu); é a barreira contra o caso em que dois heartbeats leem o
 * mesmo valor e escrevem os dois: o segundo é recusado pela linha, não pela leitura que ele não viu.
 * A expressão é {@code last_seen_at} da linha contra o parâmetro, sem {@code values()} nem função
 * condicional — a forma que roda igual no MariaDB e no H2.</p>
 */
@Repository
class NodeUptimePersistenceAdapter implements NodeUptimeRepository {

	/**
	 * Soma o trecho ao dia <b>se ele for posterior</b> ao último contato já registrado.
	 *
	 * <p>{@code received_at} é gravado em toda escrita — é o fim do trecho que chegou por último, e
	 * é contra ele que a próxima chamada compara. {@code first_seen_at}/{@code last_seen_at} cobrem
	 * a janela de medidas e usam {@code least}/{@code greatest}.</p>
	 */
	private static final String ADD_IF_AFTER = """
		insert into network_node_uptime_daily (node_id, dia, present_seconds, observed_seconds,
		  first_seen_at, last_seen_at, received_at)
		values (?, ?, ?, ?, ?, ?, ?)
		on duplicate key update
		  present_seconds = present_seconds + values(present_seconds),
		  observed_seconds = observed_seconds + values(observed_seconds),
		  first_seen_at = least(first_seen_at, values(first_seen_at)),
		  last_seen_at = greatest(last_seen_at, values(last_seen_at)),
		  received_at = values(received_at)
		""";

	private static final String DAYS = """
		select dia, present_seconds, observed_seconds from network_node_uptime_daily
		where node_id = ? and dia <= ? order by dia asc
		""";

	private static final String LAST_CONTACT = "select received_at from network_node_uptime_daily "
		+ "where node_id = ? and dia = ?";

	private static final String LAST_CONTACT_LOCKED = LAST_CONTACT + " for update";

	private static final String PURGE = "delete from network_node_uptime_daily where dia < ?";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;

	NodeUptimePersistenceAdapter(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tm) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(tm);
	}

	@Override
	public boolean add(String nodeId, LocalDate day, long presentSeconds, long observedSeconds, Instant firstSeenAt,
			Instant lastSeenAt) {
		return somar(nodeId, day, presentSeconds, observedSeconds, firstSeenAt, lastSeenAt);
	}

	private boolean somar(String nodeId, LocalDate day, long presentSeconds, long observedSeconds,
			Instant firstSeenAt, Instant lastSeenAt) {
		Instant jaContado = lerComTrava(nodeId, day);
		if (!deveSomar(jaContado, lastSeenAt)) {
			return false;
		}
		jdbc.update(ADD_IF_AFTER, nodeId, Date.valueOf(day), presentSeconds, observedSeconds,
			utc(firstSeenAt), utc(lastSeenAt), utc(lastSeenAt));
		return true;
	}

	/**
	 * A regra, isolada para ser provada sem banco: o trecho entra quando o dia ainda não tem contato,
	 * ou quando ele não é o mesmo que o último contado já fechou.
	 *
	 * <p>"Mesmo trecho" é o que termina no último contato RECEBIDO — a comparação é com a chegada,
	 * não com o maior instante, para o reenvio de um trecho fora de ordem também ser recusado. O
	 * heartbeat é republicado com o
	 * mesmo {@code lastSeenAt} (retry de rede, leitura de config repetida), e somá-lo de novo diria
	 * que o nó esteve no ar mais tempo do que existe. Um trecho que termina <b>antes</b> desse
	 * contato é tempo observado que ainda não entrou — chegou fora de ordem —, e soma; o dia
	 * acumula o que houve, não só o que chegou em sequência.</p>
	 */
	static boolean deveSomar(Instant jaContado, Instant fimDoTrecho) {
		return jaContado == null || !jaContado.equals(fimDoTrecho);
	}

	@Override
	public Instant lastContact(String nodeId, LocalDate day) {
		Object[] args = { nodeId, Date.valueOf(day) };
		List<LocalDateTime> lido = jdbc.queryForList(LAST_CONTACT, LocalDateTime.class, args);
		return lido.isEmpty() ? null : lido.get(0).toInstant(ZoneOffset.UTC);
	}

	/**
	 * Lê o último contato do dia travando a linha para a soma. O {@code for update} é do MariaDB; no
	 * dialeto de teste ele é recusado e a leitura cai para a forma simples.
	 */
	private Instant lerComTrava(String nodeId, LocalDate day) {
		Object[] args = { nodeId, Date.valueOf(day) };
		try {
			List<LocalDateTime> lido = jdbc.queryForList(LAST_CONTACT_LOCKED, LocalDateTime.class, args);
			return lido.isEmpty() ? null : lido.get(0).toInstant(ZoneOffset.UTC);
		}
		catch (DataAccessException semForUpdate) {
				// Só a recusa SYNTAX_ERROR do dialeto vale a degradação. Um erro de outro tipo (coluna,
			// conexão, concorrência) não é "este banco não tem for update" e não pode virar leitura
			// silenciosa — esconderia o defeito.
			if (!(semForUpdate.getMostSpecificCause() instanceof java.sql.SQLSyntaxErrorException)) {
				throw semForUpdate;
			}
			return lastContact(nodeId, day);
		}
	}

	@Override
	public List<NodeAvailability.Day> days(String nodeId, LocalDate day) {
		return jdbc.query(DAYS, (rs, i) -> new NodeAvailability.Day(rs.getDate("dia").toLocalDate(),
			rs.getLong("present_seconds"), rs.getLong("observed_seconds")), nodeId, Date.valueOf(day));
	}

	/**
	 * O instante em UTC, como {@code LocalDateTime}.
	 *
	 * <p>A coluna é {@code datetime(3)} (ver a migração): ela guarda o valor que receber, sem
	 * conversão de fuso. O driver, porém, converte o que lhe é dado: {@code OffsetDateTime} chega
	 * como o horário da <b>zona da JVM</b> (medido: 14:57Z gravado como 11:57 com a JVM em
	 * São Paulo), e {@code Timestamp} idem. Entregar o {@code LocalDateTime} de UTC é o que faz a
	 * coluna conter o instante — e reler com o mesmo tipo devolve o mesmo instante, em qualquer
	 * fuso.</p>
	 */
	private static LocalDateTime utc(Instant instante) {
		return LocalDateTime.ofInstant(instante, ZoneOffset.UTC);
	}

	@Override
	public int purgeBefore(LocalDate before) {
		// O parâmetro é embrulhado em Object: `update(sql, LocalDate)` seria resolvido como varargs de
		// Object[] pelo Java e o driver receberia um array no lugar da data.
		return jdbc.update(PURGE, (Object) Date.valueOf(before));
	}
}
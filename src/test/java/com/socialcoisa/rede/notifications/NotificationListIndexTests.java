package com.socialcoisa.rede.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.notifications.application.port.out.NotificationRepository;
import com.socialcoisa.rede.notifications.domain.Notification;
import com.socialcoisa.rede.notifications.domain.NotificationContext;

/**
 * O indice de {@code notifications (user_id, created_at)} existe para a lista de notificacoes
 * (issue #521 da mesma familia; V259) e nao pode mudar o que ela devolve: e otimizacao pura.
 *
 * <p>O indice que ja existia (user_id, is_read, created_at) tem o is_read NO MEIO: serve para a
 * contagem de nao lidas, mas a lista filtra so por user_id e ordena por created_at — com ele o
 * MariaDB varria tudo da pessoa e ordenava em disco (medido em MariaDB 11.4 com 200 mil linhas:
 * 200.020 leituras de linha para devolver 20; com o indice novo, 19).</p>
 *
 * <p>Este teste trava as duas coisas: o indice esta no schema (a migracao V259 rodou — o
 * schema-check do CI valida contra MariaDB real) e a lista continua devolvendo o mesmo conjunto
 * na mesma ordem.</p>
 */
@SpringBootTest
class NotificationListIndexTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private TransactionTemplate transactions;

	@Test
	void indiceDeNotificacoesPorUsuarioEDataExiste() {
		// information_schema diverge entre H2 e MariaDB; a visao INDEX_COLUMNS e a que o H2
		// expoe com as colunas de cada indice, na ordem delas.
		List<String> colunas = jdbc.queryForList("""
			select column_name from information_schema.index_columns
			where table_name = 'NOTIFICATIONS' and index_name = 'IDX_NOTIFICATIONS_USER_CREATED'
			order by ordinal_position
			""", String.class);

		// A ordem importa: a lista filtra pela pessoa e ordena pelo tempo. Com o is_read no meio,
		// como no indice antigo, a ordenacao cai fora do indice.
		assertThat(colunas).containsExactly("USER_ID", "CREATED_AT");
	}

	/**
	 * A lista continua devolvendo as mesmas notificacoes, na mesma ordem (mais recente primeiro),
	 * e a paginacao pelo `before` nao muda: o que o indice compra e o plano, nao o resultado.
	 */
	@Test
	void aListaContinuaIgualComOIndice() {
		String leitor = "user_notif_idx_" + sufixo();
		Instant agora = Instant.now();
		for (int i = 1; i <= 5; i++) {
			final int ordem = i;
			transactions.executeWithoutResult(status -> notifications.save(
				Notification.create("notif_idx_" + sufixo() + "_" + ordem, leitor, "NEW_REACTION",
					"actor_" + ordem, "Reagiram ao seu post", "", "/p/" + ordem, null,
					agora.minusSeconds(6 - ordem))));
		}

		List<Notification> primeira = notifications.findByUserId(leitor, null, NotificationContext.SOCIAL, 20);
		assertThat(primeira).hasSize(5);
		assertThat(primeira.get(0).createdAt()).isAfter(primeira.get(4).createdAt());

		// A pagina seguinte comeca aonde a anterior parou.
		Instant cursor = primeira.get(1).createdAt();
		List<Notification> segunda = notifications.findByUserId(leitor, cursor, NotificationContext.SOCIAL, 20);
		assertThat(segunda).hasSize(3);
		assertThat(segunda).noneMatch(n -> n.createdAt().isAfter(cursor));
	}

	private String sufixo() {
		return UUID.randomUUID().toString().substring(0, 8);
	}
}

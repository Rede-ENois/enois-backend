package com.socialcoisa.rede.dailygames;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.socialcoisa.rede.coin.application.CoinService;

/**
 * O fechamento do dia: as faíscas caem na carteira de quem completou, na ordem do placar,
 * uma única vez — rodar de novo não duplica prêmio nem notificação.
 */
@SpringBootTest
class DailyGameRewardsSettlementTests {

	@Autowired
	DailyGameRewardsService rewards;

	@Autowired
	JdbcTemplate jdbc;

	private String user(String label) {
		String id = "faisca-" + label + "-" + UUID.randomUUID().toString().replace("-", "");
		String handle = label + "-" + id.substring(id.length() - 8);
		jdbc.update("insert into users (id,username,name,email,open_to_work,is_mentor,created_at,updated_at) "
			+ "values (?,?,?,?,?,?,?,?)", id, handle, "Teste " + label, handle + "@test.com",
			false, false, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
		return id;
	}

	@Test
	void paysTheRankingOnceAndNotifies() {
		LocalDate day = rewards.today().minusDays(3);
		String champion = user("campea");
		String runnerUp = user("vicedia");
		String gaveUp = user("desistiu");

		jdbc.update("insert into daily_word_challenges (challenge_date,answer) select ?,? "
			+ "where not exists (select 1 from daily_word_challenges where challenge_date=?)",
			Date.valueOf(day), "AMORA", Date.valueOf(day));
		Timestamp started = Timestamp.from(Instant.now().minusSeconds(600));
		for (String id : new String[] { champion, runnerUp, gaveUp }) {
			jdbc.update("insert into daily_word_sessions (challenge_date,user_id,status,started_at) values (?,?,'WON',?)",
				Date.valueOf(day), id, started);
		}
		jdbc.update("update daily_word_sessions set status='LOST' where challenge_date=? and user_id=?",
			Date.valueOf(day), gaveUp);
		jdbc.update("insert into daily_word_guesses values (?,?,?,?,?,?)", Date.valueOf(day), champion, 1,
			"req-c1-" + day, "AMORA", "CORRECT,CORRECT,CORRECT,CORRECT,CORRECT");
		jdbc.update("insert into daily_word_guesses values (?,?,?,?,?,?)", Date.valueOf(day), runnerUp, 1,
			"req-r1-" + day, "AMIGO", "ABSENT,ABSENT,ABSENT,PRESENT,PRESENT");
		jdbc.update("insert into daily_word_guesses values (?,?,?,?,?,?)", Date.valueOf(day), runnerUp, 2,
			"req-r2-" + day, "AMORA", "CORRECT,CORRECT,CORRECT,CORRECT,CORRECT");

		assertThat(rewards.settle(day)).isEqualTo(2);

		assertThat(prizeOf(day, champion)).isEqualTo(CoinService.FAIS(10L));
		assertThat(prizeOf(day, runnerUp)).isEqualTo(CoinService.FAIS(5L));
		assertThat(jdbc.queryForObject("select count(*) from coin_entries where ref_id like ?",
			Integer.class, "jogos:palavras:" + day + ":" + gaveUp + "%")).isZero();

		assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and kind=?",
			Integer.class, champion, "JOGO_DIARIO")).isEqualTo(1);

		// Reabrir o mesmo dia não paga de novo.
		assertThat(rewards.settle(day)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and kind=?",
			Integer.class, champion, "JOGO_DIARIO")).isEqualTo(1);
	}

	private long prizeOf(LocalDate day, String userId) {
		return jdbc.queryForObject("select amount from coin_entries where account_id=? and ref_id=?", Long.class,
			userId, "jogos:palavras:" + day + ":" + userId);
	}
}

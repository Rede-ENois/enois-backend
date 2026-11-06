package com.socialcoisa.rede.dailygames;

import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.coin.application.CoinService;
import com.socialcoisa.rede.dailygames.domain.PlayPlausibility;
import com.socialcoisa.rede.shared.security.ActionThrottle;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class DailyMazeService {

	private static final Logger LOG = LoggerFactory.getLogger(DailyMazeService.class);

	/** Moves one account may send per second; the client sends one move per answered request. */
	static final int MOVES_PER_SECOND = 20;

	private static final String RANKED_SESSIONS = "select s.user_id,u.username,u.name,s.started_at,s.completed_at,s.moves "
		+ "from daily_maze_sessions s join users u on u.id=s.user_id "
		+ "where s.challenge_date=? and s.completed_at is not null and s.integrity_flag is null";

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final CoinService coins;
	private final ActionThrottle throttle;
	private final SecureRandom random = new SecureRandom();

	public DailyMazeService(JdbcTemplate jdbc, Clock clock, CoinService coins, ActionThrottle throttle) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.coins = coins;
		this.throttle = throttle;
	}

	public record State(LocalDate date, String status, int[] passages, int position, int moves, String startedAt,
			String completedAt) {
	}

	public record Entry(String username, String name, long elapsedMillis, int moves) {
	}

	public record Ranking(List<Entry> items) {
	}

	private record Session(int position, int moves, Timestamp started, Timestamp completed) {
	}

	private record Row(String userId, Entry entry) {
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
	}

	private LocalDate requestedDay(LocalDate requested) {
		LocalDate day = requested == null ? today() : requested;
		if (day.isAfter(today()) || day.isBefore(today().minusDays(1))) {
			throw new ApiException(400, "INVALID_CHALLENGE_DATE", "Desafio fora do prazo de retomada.");
		}
		return day;
	}

	private List<Session> sessions(Date day, String user, boolean lock) {
		return jdbc.query("select position,moves,started_at,completed_at from daily_maze_sessions "
			+ "where challenge_date=? and user_id=?" + (lock ? " for update" : ""),
			(r, n) -> new Session(r.getInt(1), r.getInt(2), r.getTimestamp(3), r.getTimestamp(4)), day, user);
	}

	private long seed(Date date) {
		return jdbc.queryForObject("select maze_seed from daily_maze_challenges where challenge_date=?", Long.class,
			date);
	}

	private State stateFor(LocalDate day, String user) {
		Date date = Date.valueOf(day);
		var list = sessions(date, user, false);
		if (list.isEmpty()) {
			return new State(day, "NOT_STARTED", null, 0, 0, null, null);
		}
		Session s = list.get(0);
		return new State(day, s.completed() == null ? "IN_PROGRESS" : "WON", MazeRules.generate(seed(date)),
			s.position(), s.moves(), s.started().toInstant().toString(),
			s.completed() == null ? null : s.completed().toInstant().toString());
	}

	@Transactional(readOnly = true)
	public State state(String user, LocalDate date) {
		return stateFor(requestedDay(date), user);
	}

	@Transactional
	public State start(String user) {
		LocalDate day = today();
		Date date = Date.valueOf(day);
		jdbc.update("insert into daily_maze_challenges (challenge_date,maze_seed) values (?,?) "
			+ "on duplicate key update challenge_date=challenge_date", date, random.nextLong());
		jdbc.update("insert into daily_maze_sessions (challenge_date,user_id,position,moves,started_at) "
			+ "values (?,?,0,0,?) on duplicate key update user_id=user_id", date, user, Timestamp.from(clock.instant()));
		return stateFor(day, user);
	}

	@Transactional
	public State move(String user, String direction, String requestId, LocalDate requestedDate) {
		if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,80}")) {
			throw new ApiException(400, "INVALID_REQUEST_ID", "Identificador invalido.");
		}
		throttle.exigePermitido("daily-maze-move", user, MOVES_PER_SECOND, Duration.ofSeconds(1));
		LocalDate day = requestedDay(requestedDate);
		Date date = Date.valueOf(day);
		var list = sessions(date, user, true);
		if (list.isEmpty()) {
			throw new ApiException(409, "GAME_NOT_STARTED", "Inicie o desafio primeiro.");
		}
		Session s = list.get(0);
		var existing = jdbc.query("select direction from daily_maze_moves where challenge_date=? and user_id=? "
			+ "and request_id=?", (r, n) -> r.getString(1), date, user, requestId);
		if (!existing.isEmpty()) {
			if (!existing.get(0).equals(direction)) {
				throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Identificador ja usado.");
			}
			return stateFor(day, user);
		}
		if (s.completed() != null) {
			throw new ApiException(409, "GAME_FINISHED", "Desafio encerrado.");
		}
		int[] maze = MazeRules.generate(seed(date));
		int next;
		try {
			next = MazeRules.move(maze, s.position(), direction);
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(400, "INVALID_MOVE", "Movimento invalido.");
		}
		jdbc.update("insert into daily_maze_moves (challenge_date,user_id,request_id,direction) values (?,?,?,?)",
			date, user, requestId, direction);
		boolean finished = next == MazeRules.EXIT;
		Timestamp now = Timestamp.from(clock.instant());
		String flag = null;
		if (finished && PlayPlausibility.mazeTooFast(elapsedMillis(s.started(), now), MazeRules.shortestRoute(maze))) {
			flag = PlayPlausibility.TOO_FAST;
			LOG.info("daily_game_run_flagged game=maze day={} reason={}", day, flag);
		}
		jdbc.update("update daily_maze_sessions set position=?,moves=?,completed_at=?,integrity_flag=? "
			+ "where challenge_date=? and user_id=?", next, s.moves() + 1, finished ? now : null, flag, date, user);
		if (finished) {
			coins.recordPresence(user);
		}
		return stateFor(day, user);
	}

	private static long elapsedMillis(Timestamp started, Timestamp completed) {
		return Math.max(0, Duration.between(started.toInstant(), completed.toInstant()).toMillis());
	}

	/** Finished, unflagged runs in ranking order: fastest, then fewest moves, then username. */
	private List<Row> ranked(LocalDate day) {
		return jdbc.query(RANKED_SESSIONS,
			(r, n) -> new Row(r.getString(1), new Entry(r.getString(2), r.getString(3),
				elapsedMillis(r.getTimestamp(4), r.getTimestamp(5)), r.getInt(6))),
			Date.valueOf(day))
			.stream()
			.sorted(Comparator.comparingLong((Row row) -> row.entry().elapsedMillis())
				.thenComparingInt(row -> row.entry().moves())
				.thenComparing(row -> row.entry().username()))
			.toList();
	}

	@Transactional(readOnly = true)
	public Ranking leaderboard() {
		return new Ranking(ranked(today()).stream().limit(100).map(Row::entry).toList());
	}

	/** Accounts in the day's ranking order, without the 100 cap: the prize queue. */
	@Transactional(readOnly = true)
	public List<String> payoutOrder(LocalDate day) {
		return ranked(day).stream().map(Row::userId).toList();
	}
}

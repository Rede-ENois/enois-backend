package com.socialcoisa.rede.dailygames;

import java.security.SecureRandom;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class DailyWordService {
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    // An intentionally small starter set; expand only from a reviewed, licensed source.
    private static final List<String> ANSWERS = List.of("AMIGO", "AMORA", "AREIA", "BARCO", "BEIJO", "BICHO", "BOLSA", "BRISA", "CAMPO", "CANTO", "CARRO", "CASAS", "CHUVA", "CINZA", "COBRA", "CORDA", "CORPO", "DANCA", "DEDOS", "FESTA", "FRUTA", "GATOS", "GRAMA", "GRUPO", "LAGOA", "LIVRO", "LUZES", "MAGIA", "MUNDO", "NOITE", "NUVEM", "OLHAR", "ONDAS", "OUVIR", "PEDRA", "PEIXE", "PLANO", "PONTE", "PORTA", "PRAIA", "QUERO", "RISCO", "RITMO", "ROCHA", "SONHO", "TARDE", "TERRA", "TIGRE", "VENTO");
    private static final Set<String> ALLOWED = loadAllowed();
    private static Set<String> loadAllowed() {
        try (var reader = new BufferedReader(new InputStreamReader(new ClassPathResource("dailygames/palpites-pt-br.txt").getInputStream(), StandardCharsets.UTF_8))) {
            var words = reader.lines().collect(Collectors.toSet());
            words.addAll(ANSWERS);
            return Set.copyOf(words);
        } catch (Exception e) { throw new IllegalStateException("Vocabulario de palpites indisponivel", e); }
    }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DailyWordService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    LocalDate today() { return LocalDate.now(clock.withZone(ZONE)); }
    private LocalDate requestedDay(LocalDate requested) {
        LocalDate day = requested == null ? today() : requested;
        if (day.isAfter(today()) || day.isBefore(today().minusDays(1))) throw new ApiException(400,"INVALID_CHALLENGE_DATE","Desafio fora do prazo de retomada.");
        return day;
    }
    public record Guess(String word, List<String> marks) {}
    public record State(LocalDate date, String status, List<Guess> guesses, int streak) {}
    public record Entry(String username, String name, int attempts, String completedAt) {}
    public record Ranking(List<Entry> items) {}

    @Transactional(readOnly = true)
    public State state(String user, LocalDate date) { return stateFor(requestedDay(date), user); }

    private State stateFor(LocalDate day, String user) {
        List<String> statuses = jdbc.query("select status from daily_word_sessions where challenge_date=? and user_id=?", (r,n) -> r.getString(1), Date.valueOf(day), user);
        List<Guess> guesses = jdbc.query("select guess, marks from daily_word_guesses where challenge_date=? and user_id=? order by attempt_no", (r,n) -> new Guess(r.getString(1), Arrays.asList(r.getString(2).split(","))), Date.valueOf(day), user);
        List<LocalDate> dates = jdbc.query("select challenge_date from daily_word_sessions where user_id=? and status in ('WON','LOST') and challenge_date<=? order by challenge_date desc", (r,n) -> r.getDate(1).toLocalDate(), user, Date.valueOf(day));
        int streak = 0;
        LocalDate expected = dates.isEmpty() || dates.get(0).equals(day) ? day : day.minusDays(1);
        for (LocalDate d : dates) { if (!d.equals(expected)) break; streak++; expected = expected.minusDays(1); }
        return new State(day, statuses.isEmpty() ? "NOT_STARTED" : statuses.get(0), guesses, streak);
    }

    @Transactional
    public State start(String user) {
        LocalDate day = today(); Date date = Date.valueOf(day);
        jdbc.update("insert into daily_word_challenges (challenge_date,answer) values (?,?) on duplicate key update challenge_date=challenge_date", date, ANSWERS.get(random.nextInt(ANSWERS.size())));
        jdbc.update("insert into daily_word_sessions (challenge_date,user_id,status,started_at) values (?,?,'IN_PROGRESS',?) on duplicate key update user_id=user_id",date,user,Timestamp.from(clock.instant()));
        return stateFor(day,user);
    }

    @Transactional
    public State guess(String user, String rawGuess, String requestId, LocalDate requestedDate) {
        String guess = WordRules.normalize(rawGuess);
        if (!ALLOWED.contains(guess)) throw new ApiException(400,"INVALID_GUESS","Palavra fora do vocabulario disponivel.");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{8,80}")) throw new ApiException(400,"INVALID_REQUEST_ID","Identificador de tentativa invalido.");
        LocalDate day = requestedDay(requestedDate);
        Date date = Date.valueOf(day);
        List<String> sessions = jdbc.query("select status from daily_word_sessions where challenge_date=? and user_id=? for update", (r,n)->r.getString(1),date,user);
        if (sessions.isEmpty()) throw new ApiException(409,"GAME_NOT_STARTED","Inicie o desafio primeiro.");
        String status = sessions.get(0);
        List<String> previous = jdbc.query("select guess from daily_word_guesses where challenge_date=? and user_id=? and request_id=?", (r,n)->r.getString(1),date,user,requestId);
        if (!previous.isEmpty()) {
            if (!previous.get(0).equals(guess)) throw new ApiException(409,"IDEMPOTENCY_CONFLICT","Identificador ja usado com outro palpite.");
            return stateFor(day,user);
        }
        if (!"IN_PROGRESS".equals(status)) throw new ApiException(409,"GAME_FINISHED","Desafio do dia encerrado.");
        List<String> already = jdbc.query("select guess from daily_word_guesses where challenge_date=? and user_id=? and guess=?", (r,n)->r.getString(1), date, user, guess);
        if (!already.isEmpty()) throw new ApiException(409,"GUESS_ALREADY_USED","Voce ja usou essa palavra hoje; ela nao consome nova tentativa.");
        String answer = jdbc.queryForObject("select answer from daily_word_challenges where challenge_date=?",String.class,date);
        Integer count = jdbc.queryForObject("select count(*) from daily_word_guesses where challenge_date=? and user_id=?",Integer.class,date,user);
        int attempt = count + 1;
        String marks = String.join(",",WordRules.evaluate(answer,guess));
        jdbc.update("insert into daily_word_guesses (challenge_date,user_id,attempt_no,request_id,guess,marks) values (?,?,?,?,?,?)",date,user,attempt,requestId,guess,marks);
        if (guess.equals(answer) || attempt == 6) jdbc.update("update daily_word_sessions set status=?, completed_at=? where challenge_date=? and user_id=?",guess.equals(answer)?"WON":"LOST",Timestamp.from(clock.instant()),date,user);
        return stateFor(day,user);
    }

    @Transactional(readOnly = true)
    public Ranking leaderboard() {
        List<Entry> items = jdbc.query("select u.username,u.name,count(g.attempt_no) as attempts,s.completed_at from daily_word_sessions s join users u on u.id=s.user_id join daily_word_guesses g on g.challenge_date=s.challenge_date and g.user_id=s.user_id where s.challenge_date=? and s.status='WON' group by u.username,u.name,s.completed_at order by attempts asc,s.completed_at asc,u.username asc limit 100", (r,n)-> new Entry(r.getString(1),r.getString(2),r.getInt(3),r.getTimestamp(4).toInstant().toString()), Date.valueOf(today()));
        return new Ranking(items);
    }
}

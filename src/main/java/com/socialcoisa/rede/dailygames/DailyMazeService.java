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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class DailyMazeService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public DailyMazeService(JdbcTemplate jdbc, Clock clock) { this.jdbc=jdbc; this.clock=clock; }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo"))); }
    private LocalDate requestedDay(LocalDate requested) {
        LocalDate day = requested == null ? today() : requested;
        if (day.isAfter(today()) || day.isBefore(today().minusDays(1))) throw new ApiException(400,"INVALID_CHALLENGE_DATE","Desafio fora do prazo de retomada.");
        return day;
    }
    public record State(LocalDate date,String status,int[] passages,int position,int moves,String startedAt,String completedAt) {}
    public record Entry(String username,String name,long elapsedMillis,int moves) {}
    public record Ranking(List<Entry> items) {}
    private record Session(int position,int moves,Timestamp started,Timestamp completed) {}
    private List<Session> sessions(Date day,String user,boolean lock) {
        return jdbc.query("select position,moves,started_at,completed_at from daily_maze_sessions where challenge_date=? and user_id=?"+(lock?" for update":""), (r,n)->new Session(r.getInt(1),r.getInt(2),r.getTimestamp(3),r.getTimestamp(4)),day,user);
    }
    private State stateFor(LocalDate day,String user) {
        Date date=Date.valueOf(day);
        var list=sessions(date,user,false);
        if (list.isEmpty()) return new State(day,"NOT_STARTED",null,0,0,null,null);
        Session s=list.get(0);
        long seed=jdbc.queryForObject("select maze_seed from daily_maze_challenges where challenge_date=?",Long.class,date);
        return new State(day,s.completed()==null?"IN_PROGRESS":"WON",MazeRules.generate(seed),s.position(),s.moves(),s.started().toInstant().toString(),s.completed()==null?null:s.completed().toInstant().toString());
    }
    @Transactional(readOnly=true)
    public State state(String user, LocalDate date) { return stateFor(requestedDay(date),user); }
    @Transactional
    public State start(String user) {
        LocalDate day=today(); Date date=Date.valueOf(day);
        jdbc.update("insert into daily_maze_challenges (challenge_date,maze_seed) values (?,?) on duplicate key update challenge_date=challenge_date",date,random.nextLong());
        jdbc.update("insert into daily_maze_sessions (challenge_date,user_id,position,moves,started_at) values (?,?,0,0,?) on duplicate key update user_id=user_id",date,user,Timestamp.from(clock.instant()));
        return stateFor(day,user);
    }
    @Transactional
    public State move(String user,String direction,String requestId,LocalDate requestedDate) {
        if (requestId==null || !requestId.matches("[A-Za-z0-9_-]{8,80}")) throw new ApiException(400,"INVALID_REQUEST_ID","Identificador invalido.");
        LocalDate day=requestedDay(requestedDate); Date date=Date.valueOf(day);
        var list=sessions(date,user,true);
        if (list.isEmpty()) throw new ApiException(409,"GAME_NOT_STARTED","Inicie o desafio primeiro.");
        Session s=list.get(0);
        var existing=jdbc.query("select direction from daily_maze_moves where challenge_date=? and user_id=? and request_id=?",(r,n)->r.getString(1),date,user,requestId);
        if (!existing.isEmpty()) {
            if (!existing.get(0).equals(direction)) throw new ApiException(409,"IDEMPOTENCY_CONFLICT","Identificador ja usado.");
            return stateFor(day,user);
        }
        if (s.completed()!=null) throw new ApiException(409,"GAME_FINISHED","Desafio encerrado.");
        long seed=jdbc.queryForObject("select maze_seed from daily_maze_challenges where challenge_date=?",Long.class,date);
        int next;
        try { next=MazeRules.move(MazeRules.generate(seed),s.position(),direction); }
        catch (IllegalArgumentException ex) { throw new ApiException(400,"INVALID_MOVE","Movimento invalido."); }
        jdbc.update("insert into daily_maze_moves (challenge_date,user_id,request_id,direction) values (?,?,?,?)",date,user,requestId,direction);
        jdbc.update("update daily_maze_sessions set position=?,moves=?,completed_at=? where challenge_date=? and user_id=?",next,s.moves()+1,next==MazeRules.SIZE*MazeRules.SIZE-1?Timestamp.from(clock.instant()):null,date,user);
        return stateFor(day,user);
    }
    @Transactional(readOnly=true)
    public Ranking leaderboard() {
        var entries=jdbc.query("select u.username,u.name,s.started_at,s.completed_at,s.moves from daily_maze_sessions s join users u on u.id=s.user_id where s.challenge_date=? and s.completed_at is not null",(r,n)->new Entry(r.getString(1),r.getString(2),Math.max(0,Duration.between(r.getTimestamp(3).toInstant(),r.getTimestamp(4).toInstant()).toMillis()),r.getInt(5)),Date.valueOf(today()));
        return new Ranking(entries.stream().sorted(Comparator.comparingLong(Entry::elapsedMillis).thenComparingInt(Entry::moves).thenComparing(Entry::username)).limit(100).toList());
    }
}

package com.socialcoisa.rede.dailygames;

import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.socialcoisa.rede.dailygames.blocks.BlocksEngine;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class DailyBlocksService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    private final com.socialcoisa.rede.coin.application.CoinService coins;
public DailyBlocksService(JdbcTemplate jdbc,Clock clock,com.socialcoisa.rede.coin.application.CoinService coins) { this.coins=coins; this.jdbc=jdbc; this.clock=clock; }
    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo"))); }
    private LocalDate day(LocalDate requested) {
        LocalDate result=requested==null?today():requested;
        if (result.isAfter(today()) || result.isBefore(today().minusDays(1))) throw new ApiException(400,"INVALID_CHALLENGE_DATE","Desafio fora do prazo de retomada.");
        return result;
    }
    private record Session(Timestamp started,Timestamp lastAction,Timestamp completed,String status,int score,int lines) {}
    public record State(LocalDate date,String status,int score,int lines,int actions,List<String> board,List<BlocksEngine.Cell> activeCells,List<BlocksEngine.Cell> nextCells,int rulesVersion,List<Integer> pieces) {}
    public record Entry(String username,String name,int score,int lines,long elapsedMillis) {}
    public record Ranking(List<Entry> items) {}
    private List<Session> sessions(Date d,String user,boolean lock) {
        return jdbc.query("select started_at,last_action_at,completed_at,status,score,`lines` from daily_blocks_sessions where challenge_date=? and user_id=?"+(lock?" for update":""),(r,n)->new Session(r.getTimestamp(1),r.getTimestamp(2),r.getTimestamp(3),r.getString(4),r.getInt(5),r.getInt(6)),d,user);
    }
    private BlocksEngine.Result replay(Date d,String user) {
        var challenge=jdbc.queryForMap("select seed,target_lines from daily_blocks_challenges where challenge_date=?",d);
        long seed=((Number)challenge.get("seed")).longValue();
        int target=((Number)challenge.get("target_lines")).intValue();
        Timestamp started=jdbc.queryForObject("select started_at from daily_blocks_sessions where challenge_date=? and user_id=?",Timestamp.class,d,user);
        Instant t0=started.toInstant();
        // O relógio do servidor manda no tick: 1 tick = 100 ms desde o início da sessão.
        // Cada ação entra no tick do seu timestamp; a gravidade roda no tempo real, igual ao cliente.
        var actions=jdbc.query("select action,created_at from daily_blocks_actions where challenge_date=? and user_id=? order by action_no",
            (r,n)->new Object[]{BlocksEngine.Action.valueOf(r.getString(1)),r.getTimestamp(2).toInstant()},d,user);
        List<BlocksEngine.Event> events=new java.util.ArrayList<>();
        // Acoes alem da janela de 6000 ticks nao pertencem mais a partida (TIMED_OUT): sao ignoradas no replay.
        int previousTick=0;
        for (Object[] row:actions) {
            int tick=tickOf(t0,(Instant)row[1]);
            if (tick>previousTick && tick<BlocksEngine.MAX_TICKS) { events.add(new BlocksEngine.Event(tick,(BlocksEngine.Action)row[0])); previousTick=tick; }
        }
        int elapsed=Math.max(ticksSince(t0,clock.instant()), events.isEmpty() ? 0 : events.get(events.size()-1).tick());
        return BlocksEngine.replay(seed,target,events,elapsed);
    }
    private static int clampTicks(long millis){return (int)Math.min(BlocksEngine.MAX_TICKS,Math.max(0,millis/BlocksEngine.TICK_MILLIS));}
    private static int tickOf(Instant t0,Instant at){return Math.max(1,clampTicks(Duration.between(t0,at).toMillis()));}
    private static int ticksSince(Instant t0,Instant at){return clampTicks(Duration.between(t0,at).toMillis());}
    private State stateFor(LocalDate day,String user) {
        Date d=Date.valueOf(day);
        if (sessions(d,user,false).isEmpty()) return new State(day,"NOT_STARTED",0,0,0,List.of(),List.of(),List.of(),BlocksEngine.RULES_VERSION,List.of());
        var r=replay(d,user);
        List<Integer> pieces=List.of();
        if (r.status()==BlocksEngine.Status.PLAYING) {
            long seed=((Number)jdbc.queryForObject("select seed from daily_blocks_challenges where challenge_date=?",Long.class,d)).longValue();
            pieces=BlocksEngine.pieceSequence(seed,100,r.piecesSpawned());
        }
        return new State(day,r.status().name(),r.score(),r.lines(),r.actions(),r.board(),r.activeCells(),r.nextCells(),r.rulesVersion(),pieces);
    }
    @Transactional(readOnly=true) public State state(String user,LocalDate date) { return stateFor(day(date),user); }
    @Transactional public State start(String user) {
        LocalDate day=today(); Date d=Date.valueOf(day);
        jdbc.update("insert into daily_blocks_challenges (challenge_date,seed,target_lines,rules_version) values (?,?,3,?) on duplicate key update challenge_date=challenge_date",d,random.nextLong(),BlocksEngine.RULES_VERSION);
        jdbc.update("insert into daily_blocks_sessions (challenge_date,user_id,started_at,status,score,`lines`) values (?,?,?,'PLAYING',0,0) on duplicate key update user_id=user_id",d,user,Timestamp.from(clock.instant()));
        return stateFor(day,user);
    }
    @Transactional public State action(String user,String action,String requestId,LocalDate requestedDate) {
        if (requestId==null || !requestId.matches("[A-Za-z0-9_-]{8,80}")) throw new ApiException(400,"INVALID_REQUEST_ID","Identificador invalido.");
        BlocksEngine.Action command;
        try { command=BlocksEngine.Action.valueOf(action); } catch (Exception e) { throw new ApiException(400,"INVALID_ACTION","Acao invalida."); }
        LocalDate day=day(requestedDate); Date d=Date.valueOf(day);
        var sessions=sessions(d,user,true);
        if (sessions.isEmpty()) throw new ApiException(409,"GAME_NOT_STARTED","Inicie o desafio primeiro.");
        Session s=sessions.get(0);
        var prior=jdbc.query("select action from daily_blocks_actions where challenge_date=? and user_id=? and request_id=?",(r,n)->r.getString(1),d,user,requestId);
        if (!prior.isEmpty()) {
            if (!prior.get(0).equals(action)) throw new ApiException(409,"IDEMPOTENCY_CONFLICT","Identificador ja usado.");
            return stateFor(day,user);
        }
        if (!s.status().equals("PLAYING")) throw new ApiException(409,"GAME_FINISHED","Desafio encerrado.");
        if (Duration.between(s.started().toInstant(),clock.instant()).toMillis()>=BlocksEngine.MAX_TICKS*(long)BlocksEngine.TICK_MILLIS) {
            jdbc.update("update daily_blocks_sessions set completed_at=?,status=? where challenge_date=? and user_id=?",Timestamp.from(clock.instant()),BlocksEngine.Status.TIMED_OUT.name(),d,user);
            throw new ApiException(409,"GAME_FINISHED","Janela de tempo do desafio encerrada.");
        }
        if (s.lastAction()!=null && Duration.between(s.lastAction().toInstant(),clock.instant()).toMillis()<80) throw new ApiException(429,"ACTION_TOO_FAST","Aguarde antes da proxima acao.");
        int count=jdbc.queryForObject("select count(*) from daily_blocks_actions where challenge_date=? and user_id=?",Integer.class,d,user);
        if (count>=BlocksEngine.MAX_ACTIONS) throw new ApiException(409,"ACTION_LIMIT","Limite de acoes atingido.");
        jdbc.update("insert into daily_blocks_actions (challenge_date,user_id,action_no,request_id,action,created_at) values (?,?,?,?,?,?)",d,user,count+1,requestId,command.name(),Timestamp.from(clock.instant()));
        BlocksEngine.Result result=replay(d,user);
        Timestamp now=Timestamp.from(clock.instant());
        jdbc.update("update daily_blocks_sessions set last_action_at=?,completed_at=?,status=?,score=?,`lines`=? where challenge_date=? and user_id=?",now,result.status()==BlocksEngine.Status.PLAYING?null:now,result.status().name(),result.score(),result.lines(),d,user);
        if (result.status()!=BlocksEngine.Status.PLAYING) coins.recordPresence(user);
        return stateFor(day,user);
    }
    @Transactional(readOnly=true) public Ranking leaderboard() {
        var entries=jdbc.query("select u.username,u.name,s.score,s.`lines`,s.started_at,s.completed_at from daily_blocks_sessions s join users u on u.id=s.user_id where s.challenge_date=? and s.status='WON'",(r,n)->new Entry(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),Math.max(0,Duration.between(r.getTimestamp(5).toInstant(),r.getTimestamp(6).toInstant()).toMillis())),Date.valueOf(today()));
        return new Ranking(entries.stream().sorted(Comparator.comparingInt(Entry::score).reversed().thenComparingLong(Entry::elapsedMillis).thenComparing(Entry::username)).limit(100).toList());
    }

    /** Contas na ordem do placar do dia, sem teto de 100 e sem quem não limpou as linhas: é a fila do prêmio. */
    @Transactional(readOnly=true) public List<String> payoutOrder(LocalDate day) {
        record Row(String userId,String username,int score,long elapsedMillis) {}
        var rows=jdbc.query("select s.user_id,u.username,s.score,s.started_at,s.completed_at from daily_blocks_sessions s join users u on u.id=s.user_id where s.challenge_date=? and s.status='WON'",(r,n)->new Row(r.getString(1),r.getString(2),r.getInt(3),Math.max(0,Duration.between(r.getTimestamp(4).toInstant(),r.getTimestamp(5).toInstant()).toMillis())),Date.valueOf(day));
        return rows.stream().sorted(Comparator.comparingInt(Row::score).reversed().thenComparingLong(Row::elapsedMillis).thenComparing(Row::username)).map(Row::userId).toList();
    }
}

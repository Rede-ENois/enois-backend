package com.socialcoisa.rede.dailygames;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
@RestController
@RequestMapping("/daily-games/maze")
public class DailyMazeController {
    private final DailyMazeService games;
    private final CurrentUserResolver users;
    public DailyMazeController(DailyMazeService games,CurrentUserResolver users) { this.games=games; this.users=users; }
    public record MoveRequest(String direction,String requestId) {}
    @GetMapping("/today") public DailyMazeService.State today(@RequestHeader(value="X-Session-Token",required=false) String token,@RequestParam(required=false) LocalDate date) { return games.state(users.resolve(token).id().value(),date); }
    @PostMapping("/today/start") public DailyMazeService.State start(@RequestHeader(value="X-Session-Token",required=false) String token) { return games.start(users.resolve(token).id().value()); }
    @PostMapping("/today/moves") public DailyMazeService.State move(@RequestHeader(value="X-Session-Token",required=false) String token,@RequestBody MoveRequest request,@RequestParam(required=false) LocalDate date) { return games.move(users.resolve(token).id().value(),request.direction(),request.requestId(),date); }
    @GetMapping("/leaderboard") public DailyMazeService.Ranking leaderboard() { return games.leaderboard(); }
}

package com.socialcoisa.rede.dailygames;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
@RestController
@RequestMapping("/daily-games/blocks")
public class DailyBlocksController {
    private final DailyBlocksService games;
    private final CurrentUserResolver users;
    public DailyBlocksController(DailyBlocksService games,CurrentUserResolver users) { this.games=games; this.users=users; }
    public record ActionRequest(String action,String requestId) {}
    @GetMapping("/today") public DailyBlocksService.State today(@RequestHeader(value="X-Session-Token",required=false) String token,@RequestParam(required=false) LocalDate date) { return games.state(users.resolve(token).id().value(),date); }
    @PostMapping("/today/start") public DailyBlocksService.State start(@RequestHeader(value="X-Session-Token",required=false) String token) { return games.start(users.resolve(token).id().value()); }
    @PostMapping("/today/actions") public DailyBlocksService.State action(@RequestHeader(value="X-Session-Token",required=false) String token,@RequestBody ActionRequest request,@RequestParam(required=false) LocalDate date) { return games.action(users.resolve(token).id().value(),request.action(),request.requestId(),date); }
    @GetMapping("/leaderboard") public DailyBlocksService.Ranking leaderboard() { return games.leaderboard(); }
}

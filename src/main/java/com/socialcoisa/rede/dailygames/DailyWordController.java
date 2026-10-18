package com.socialcoisa.rede.dailygames;

import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@RestController
@RequestMapping("/daily-games/words")
public class DailyWordController {
    private final DailyWordService games;
    private final CurrentUserResolver users;
    public DailyWordController(DailyWordService games, CurrentUserResolver users) { this.games=games; this.users=users; }
    public record GuessRequest(String guess, String requestId) {}
    @GetMapping("/today")
    public DailyWordService.State today(@RequestHeader(value="X-Session-Token",required=false) String token, @RequestParam(required=false) LocalDate date) {
        return games.state(users.resolve(token).id().value(),date);
    }
    @PostMapping("/today/start")
    public DailyWordService.State start(@RequestHeader(value="X-Session-Token",required=false) String token) {
        return games.start(users.resolve(token).id().value());
    }
    @PostMapping("/today/guesses")
    public DailyWordService.State guess(@RequestHeader(value="X-Session-Token",required=false) String token, @RequestBody GuessRequest request, @RequestParam(required=false) LocalDate date) {
        return games.guess(users.resolve(token).id().value(),request.guess(),request.requestId(),date);
    }
    @GetMapping("/leaderboard")
    public DailyWordService.Ranking leaderboard() { return games.leaderboard(); }
}

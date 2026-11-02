package com.socialcoisa.rede.freelances.adapters.in.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

import com.socialcoisa.rede.freelances.application.FreelanceService;
import com.socialcoisa.rede.freelances.application.FreelanceView;
import com.socialcoisa.rede.freelances.domain.Freelance;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@RestController
class FreelanceController {

	private final FreelanceService freelances;
	private final CurrentUserResolver currentUserResolver;
	private final UserProfileRepository profiles;

	FreelanceController(FreelanceService freelances, CurrentUserResolver currentUserResolver,
			UserProfileRepository profiles) {
		this.freelances = freelances;
		this.currentUserResolver = currentUserResolver;
		this.profiles = profiles;
	}

	@GetMapping("/freelances")
	Map<String, Object> list(@RequestParam(required = false) Integer limit,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) String q,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		int size = limit == null ? 24 : Math.min(60, Math.max(1, limit));
		int offset = parseCursor(cursor);
		String viewer = viewerIdOrNull(sessionToken);
		List<FreelanceView> items = views(freelances.list(q, size, offset), viewer);
		Map<String, Object> result = new HashMap<>();
		result.put("items", items);
		result.put("nextCursor", items.size() == size ? Integer.toString(offset + size) : null);
		return result;
	}

	@GetMapping("/me/freelances")
	List<FreelanceView> mine(@RequestHeader("X-Session-Token") String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return views(freelances.mine(user.id().value()), user.id().value());
	}

	@PostMapping("/freelances")
	FreelanceView create(@RequestHeader("X-Session-Token") String sessionToken, @RequestBody CreateFreelanceRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Freelance f = freelances.create(user.id().value(), request.title(), request.scope(),
			request.budgetMin(), request.budgetMax(), request.deadline(), request.paymentMethod());
		return FreelanceView.of(f, freelances.usernameOf(user.id().value()), user.id().value());
	}

	/** Edição pelo dono: mesmo corpo da criação. Freela encerrado não edita. */
	@PatchMapping("/freelances/{id}")
	FreelanceView update(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id,
			@RequestBody CreateFreelanceRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Freelance f = freelances.update(id, user.id().value(), request.title(), request.scope(),
			request.budgetMin(), request.budgetMax(), request.deadline(), request.paymentMethod());
		return FreelanceView.of(f, freelances.usernameOf(user.id().value()), user.id().value());
	}

	/** Exclusão pelo dono. */
	@DeleteMapping("/freelances/{id}")
	Map<String, Object> delete(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		freelances.delete(id, user.id().value());
		return Map.of("id", id, "status", "DELETED");
	}

	@PostMapping("/freelances/{id}/close")
	Map<String, Object> close(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Freelance f = freelances.close(id, user.id().value());
		return Map.of("id", f.id(), "status", f.status().name());
	}

	/** Proposta: a identidade e a conversa profissionais sao decididas no servidor. */
	@PostMapping("/freelances/{id}/proposals")
	Map<String, Object> propose(@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id,
			@RequestBody ProposalRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return Map.of("conversationId", freelances.propose(id, user.id().value(), request == null ? null : request.message()));
	}

	private List<FreelanceView> views(List<Freelance> list, String viewer) {
		Map<String, String> names = new HashMap<>();
		profiles.findAllByIds(list.stream().map(Freelance::userId).distinct().toList())
			.forEach(p -> names.put(p.id(), p.username()));
		return list.stream().map(f -> FreelanceView.of(f, names.get(f.userId()), viewer)).toList();
	}

	private String viewerIdOrNull(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken).id().value();
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private static int parseCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) return 0;
		try { return Math.max(0, Integer.parseInt(cursor.trim())); } catch (NumberFormatException e) { return 0; }
	}

	record ProposalRequest(String message) {
	}

	record CreateFreelanceRequest(String title, String scope, int budgetMin, int budgetMax, String deadline,
			String paymentMethod) {
	}
}

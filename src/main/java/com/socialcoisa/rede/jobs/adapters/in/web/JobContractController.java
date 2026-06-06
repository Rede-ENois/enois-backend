package com.socialcoisa.rede.jobs.adapters.in.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/jobs")
class JobContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	JobContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping
	PageResponse<JobView> jobs(@RequestParam(required = false) String query, @RequestParam(required = false) String type,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		return new PageResponse<>(List.of(), null);
	}

	@GetMapping("/{jobId}")
	JobView job(@PathVariable String jobId) {
		return new JobView(jobId, "", "", null, "", "", "", null, List.of(), null, 0);
	}

	@PostMapping("/{jobId}/applications")
	ResponseEntity<String> apply(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId));
		return idempotency.execute(user.id(), "POST", "/api/jobs/{jobId}/applications", idempotencyKey, hash,
			() -> new CommandResponse(201, new StatusResponse(jobId, "APPLIED")));
	}

	@PostMapping("/{jobId}/saves")
	ResponseEntity<String> save(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId));
		return idempotency.execute(user.id(), "POST", "/api/jobs/{jobId}/saves", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(jobId, "SAVED")));
	}

	@DeleteMapping("/{jobId}/saves")
	ResponseEntity<String> removeSave(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId));
		return idempotency.execute(user.id(), "DELETE", "/api/jobs/{jobId}/saves", idempotencyKey, hash,
			() -> new CommandResponse(200, new StatusResponse(jobId, "UNSAVED")));
	}

	record JobView(String id, String title, String company, String logo, String location, String type, String level,
			String salary, List<String> skills, String postedAt, int matchScore) {
	}
}

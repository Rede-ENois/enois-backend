package com.socialcoisa.rede.mentoring.adapters.in.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@RequestMapping("/api/mentors")
class MentoringContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;

	MentoringContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
	}

	@GetMapping
	PageResponse<MentorView> mentors(@RequestParam(required = false) String expertise,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		return new PageResponse<>(List.of(), null);
	}

	@GetMapping("/{mentorId}")
	MentorView mentor(@PathVariable String mentorId) {
		return new MentorView(mentorId, "", List.of(), 0.0, 0, null, null);
	}

	@GetMapping("/{mentorId}/slots")
	PageResponse<MentorSlotView> slots(@PathVariable String mentorId) {
		return new PageResponse<>(List.of(), null);
	}

	@PostMapping("/{mentorId}/sessions")
	ResponseEntity<String> session(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @RequestBody MentorSessionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(new MentorSessionPayload(mentorId, request));
		return idempotency.execute(user.id(), "POST", "/api/mentors/{mentorId}/sessions", idempotencyKey, hash,
			() -> new CommandResponse(201, new StatusResponse(newId("mentor_session"), "SCHEDULED")));
	}

	private String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	record MentorView(String id, String userId, List<String> expertise, double rating, int sessions, String price,
			String nextSlot) {
	}

	record MentorSlotView(String id, String startsAt, String endsAt, boolean available) {
	}

	record MentorSessionRequest(String slotId, String message) {
	}

	record MentorSessionPayload(String mentorId, MentorSessionRequest request) {
	}
}

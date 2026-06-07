package com.socialcoisa.rede.mentoring.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.mentoring.application.MentorService;
import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/mentors")
class MentoringContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final MentorService mentorService;
	private final CursorCodec cursorCodec;

	MentoringContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			MentorService mentorService, CursorCodec cursorCodec) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.mentorService = mentorService;
		this.cursorCodec = cursorCodec;
	}

	@GetMapping
	PageResponse<MentorView> mentors(@RequestParam(required = false) String expertise,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Mentor> mentors = mentorService.list(expertise, before, limit);
		var items = mentors.stream()
			.map(m -> new MentorView(m.id(), m.userId(), m.expertise(), m.rating(), m.sessionsCount(), m.price(), null))
			.toList();
		String nextCursor = mentors.size() == limit
			? cursorCodec.encode(mentors.get(mentors.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	@GetMapping("/{mentorId}")
	MentorView mentor(@PathVariable String mentorId) {
		Mentor m = mentorService.getById(mentorId);
		return new MentorView(m.id(), m.userId(), m.expertise(), m.rating(), m.sessionsCount(), m.price(), null);
	}

	@GetMapping("/{mentorId}/slots")
	PageResponse<MentorSlotView> slots(@PathVariable String mentorId) {
		List<MentorSlot> slots = mentorService.getSlots(mentorId);
		var items = slots.stream()
			.map(s -> new MentorSlotView(s.id(),
				s.startsAt() != null ? s.startsAt().toString() : null,
				s.endsAt() != null ? s.endsAt().toString() : null,
				s.available()))
			.toList();
		return new PageResponse<>(items, null);
	}

	@PostMapping
	ResponseEntity<String> create(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateMentorRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/mentors", idempotencyKey, hash,
			() -> {
				Mentor m = mentorService.create(user.id().value(), request.expertise(), request.price());
				return new CommandResponse(201, new MentorView(m.id(), m.userId(), m.expertise(), m.rating(),
					m.sessionsCount(), m.price(), null));
			});
	}

	@PatchMapping("/{mentorId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @RequestBody CreateMentorRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId, "request", request));
		return idempotency.execute(user.id(), "PATCH", "/api/mentors/{mentorId}", idempotencyKey, hash,
			() -> {
				Mentor m = mentorService.update(mentorId, request.expertise(), request.price(), user.id().value());
				return new CommandResponse(200, new MentorView(m.id(), m.userId(), m.expertise(), m.rating(),
					m.sessionsCount(), m.price(), null));
			});
	}

	@PostMapping("/{mentorId}/slots")
	ResponseEntity<String> createSlot(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @RequestBody CreateSlotRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/mentors/{mentorId}/slots", idempotencyKey, hash,
			() -> {
				MentorSlot slot = mentorService.createSlot(mentorId, user.id().value(),
					request.startsAt(), request.endsAt());
				return new CommandResponse(201, new MentorSlotView(slot.id(),
					slot.startsAt() != null ? slot.startsAt().toString() : null,
					slot.endsAt() != null ? slot.endsAt().toString() : null,
					slot.available()));
			});
	}

	@PostMapping("/{mentorId}/sessions")
	ResponseEntity<String> session(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @RequestBody MentorSessionRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/mentors/{mentorId}/sessions", idempotencyKey, hash,
			() -> {
				MentorSession session = mentorService.scheduleSession(mentorId, user.id().value(),
					request.slotId(), request.message());
				return new CommandResponse(201, new StatusResponse(session.id(), "SCHEDULED"));
			});
	}

	record MentorView(String id, String userId, List<String> expertise, double rating, int sessions, String price,
			String nextSlot) {
	}

	record MentorSlotView(String id, String startsAt, String endsAt, boolean available) {
	}

	record MentorSessionRequest(String slotId, String message) {
	}

	record CreateMentorRequest(List<String> expertise, String price) {
	}

	record CreateSlotRequest(Instant startsAt, Instant endsAt) {
	}

}

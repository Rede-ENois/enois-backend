package com.socialcoisa.rede.mentoring.adapters.in.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.mentoring.application.MentorService;
import com.socialcoisa.rede.mentoring.domain.Mentor;
import com.socialcoisa.rede.mentoring.domain.MentorAvailability;
import com.socialcoisa.rede.mentoring.domain.MentorSession;
import com.socialcoisa.rede.mentoring.domain.MentorSlot;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/mentors")
class MentoringContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final MentorService mentorService;
	private final CursorCodec cursorCodec;
	private final UserProfileRepository profiles;

	MentoringContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			MentorService mentorService, CursorCodec cursorCodec, UserProfileRepository profiles) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.mentorService = mentorService;
		this.cursorCodec = cursorCodec;
		this.profiles = profiles;
	}

	@GetMapping
	PageResponse<MentorView> mentors(@RequestParam(required = false) String expertise,
			@RequestParam(required = false) String query,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Mentor> mentors = mentorService.list(expertise, query, before, limit);
		var items = mentors.stream()
			.map(m -> mentorView(m))
			.toList();
		String nextCursor = mentors.size() == limit
			? cursorCodec.encode(mentors.get(mentors.size() - 1).createdAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	@GetMapping("/{mentorId}")
	MentorView mentor(@PathVariable String mentorId) {
		return mentorView(mentorService.getById(mentorId));
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
	ResponseEntity<String> create(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody CreateMentorRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "POST", "/api/mentors", idempotencyKey, hash,
			() -> {
				Mentor m = mentorService.create(user.id().value(), request.expertise(), request.price(),
					request.banner(), request.meetingUrl(), request.sparksPrice());
				return new CommandResponse(201, mentorView(m));
			});
	}

	@PatchMapping("/{mentorId}")
	ResponseEntity<String> update(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @RequestBody CreateMentorRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId, "request", request));
		return idempotency.execute(user.id(), "PATCH", "/api/mentors/{mentorId}", idempotencyKey, hash,
			() -> {
				Mentor m = mentorService.update(mentorId, request.expertise(), request.price(), request.banner(),
					request.meetingUrl(), request.sparksPrice(), user.id().value());
				return new CommandResponse(200, mentorView(m));
			});
	}

	@DeleteMapping("/{mentorId}")
	ResponseEntity<String> delete(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId));
		return idempotency.execute(user.id(), "DELETE", "/api/mentors/{mentorId}", idempotencyKey, hash,
			() -> {
				mentorService.delete(mentorId, user.id().value());
				return new CommandResponse(200, new StatusResponse(mentorId, "DELETED"));
			});
	}

	@PostMapping("/{mentorId}/slots")
	ResponseEntity<String> createSlot(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @RequestBody CreateSlotRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
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
	ResponseEntity<String> session(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @RequestBody MentorSessionRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId, "request", request));
		return idempotency.execute(user.id(), "POST", "/api/mentors/{mentorId}/sessions", idempotencyKey, hash,
			() -> {
				MentorSession session = mentorService.scheduleSession(mentorId, user.id().value(),
					request.slotId(), request.message());
				return new CommandResponse(201, new StatusResponse(session.id(), "SCHEDULED"));
			});
	}

	record MentorView(String id, String userId, List<String> expertise, double rating, int sessions, String price,
			String banner, String meetingUrl, Long sparksPrice, String nextSlot) {
	}

	/** Inscrições recebidas (agenda do mentor): quem marcou, quando e o recado. Só o dono. */
	@GetMapping("/{mentorId}/sessions")
	List<MenteeSessionView> sessions(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String mentorId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<MentorSession> sessions = mentorService.sessionsOf(mentorId, user.id().value());
		Map<String, UserProfile> mentees = new HashMap<>();
		profiles.findAllByIds(sessions.stream().map(MentorSession::menteeId).distinct().toList())
			.forEach(profile -> mentees.put(profile.id(), profile));
		List<MenteeSessionView> views = new ArrayList<>();
		Map<String, MentorSlot> slots = new HashMap<>();
		mentorService.getSlots(mentorId).forEach(slot -> slots.put(slot.id(), slot));
		for (MentorSession session : sessions) {
			MentorSlot slot = session.slotId() == null ? null : slots.get(session.slotId());
			UserProfile mentee = mentees.get(session.menteeId());
			views.add(new MenteeSessionView(session.id(), session.menteeId(),
				mentee != null ? mentee.username() : null, mentee != null ? mentee.name() : null,
				mentee != null ? mentee.avatar() : null, session.slotId(),
				slot != null && slot.startsAt() != null ? slot.startsAt().toString() : null,
				slot != null && slot.endsAt() != null ? slot.endsAt().toString() : null,
				session.message(), session.status().name(), session.priceSparks(),
				session.createdAt().toString()));
		}
		views.sort((first, second) -> {
			if (first.startsAt() == null && second.startsAt() == null) return 0;
			if (first.startsAt() == null) return 1;
			if (second.startsAt() == null) return -1;
			return second.startsAt().compareTo(first.startsAt());
		});
		return views;
	}

	@GetMapping("/{mentorId}/availability")
	List<AvailabilityView> availability(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String mentorId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return toAvailabilityViews(mentorService.availabilitiesOf(mentorId, user.id().value()));
	}

	@PutMapping("/{mentorId}/availability")
	ResponseEntity<String> saveAvailability(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @RequestBody AvailabilityRequest request) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId, "request", request));
		return idempotency.execute(user.id(), "PUT", "/api/mentors/{mentorId}/availability", idempotencyKey, hash,
			() -> {
				List<MentorService.AvailabilityRule> rules = request == null || request.items() == null
					? List.of()
					: request.items().stream()
						.map(item -> new MentorService.AvailabilityRule(item.dayOfWeek(),
							item.times() == null ? List.of() : item.times()))
						.toList();
				List<MentorAvailability> saved = mentorService.saveAvailability(mentorId, user.id().value(), rules);
				return new CommandResponse(200, toAvailabilityViews(saved));
			});
	}

	private List<AvailabilityView> toAvailabilityViews(List<MentorAvailability> rules) {
		Map<Integer, List<String>> byDay = new java.util.TreeMap<>();
		for (MentorAvailability rule : rules) {
			byDay.computeIfAbsent(rule.dayOfWeek(), key -> new ArrayList<>()).add(rule.timeOfDay());
		}
		return byDay.entrySet().stream()
			.map(entry -> new AvailabilityView(entry.getKey(), List.copyOf(entry.getValue())))
			.toList();
	}

	record AvailabilityRequest(List<AvailabilityItem> items) {
	}

	record AvailabilityItem(int dayOfWeek, List<String> times) {
	}

	record AvailabilityView(int dayOfWeek, List<String> times) {
	}

	record MenteeSessionView(String id, String menteeId, String menteeUsername, String menteeName, String menteeAvatar,
			String slotId, String startsAt, String endsAt, String message, String status, Long priceSparks,
			String createdAt) {
	}

	@org.springframework.web.bind.annotation.DeleteMapping("/{mentorId}/slots/{slotId}")
	ResponseEntity<Void> deleteSlot(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@PathVariable String mentorId, @PathVariable String slotId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		mentorService.deleteSlot(mentorId, slotId, user.id().value());
		return ResponseEntity.noContent().build();
	}

	/** Minhas sessões como mentorado: o que eu agendei, por quanto e em que pé está. */
	@GetMapping("/sessions/mine")
	List<MySessionView> mySessions(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		List<MentorSession> sessions = mentorService.sessionsByMentee(user.id().value());
		Map<String, Mentor> mentors = new HashMap<>();
		for (MentorSession session : sessions) {
			Mentor mentor = mentorService.mentorOrNull(session.mentorId());
			if (mentor != null) {
				mentors.put(session.mentorId(), mentor);
			}
		}
		Map<String, UserProfile> owners = new HashMap<>();
		profiles.findAllByIds(mentors.values().stream().map(Mentor::userId).distinct().toList())
			.forEach(profile -> owners.put(profile.id(), profile));
		Map<String, MentorSlot> slots = new HashMap<>();
		for (MentorSession session : sessions) {
			if (session.slotId() == null || slots.containsKey(session.slotId())) {
				continue;
			}
			MentorSlot slot = mentorService.slotOf(session.slotId());
			if (slot != null) {
				slots.put(session.slotId(), slot);
			}
		}
		List<MySessionView> views = new ArrayList<>();
		for (MentorSession session : sessions) {
			Mentor mentor = mentors.get(session.mentorId());
			UserProfile owner = mentor == null ? null : owners.get(mentor.userId());
			MentorSlot slot = session.slotId() == null ? null : slots.get(session.slotId());
			views.add(new MySessionView(session.id(), session.mentorId(),
				owner != null ? owner.username() : null, owner != null ? owner.name() : null,
				owner != null ? owner.avatar() : null,
				slot != null && slot.startsAt() != null ? slot.startsAt().toString() : null,
				slot != null && slot.endsAt() != null ? slot.endsAt().toString() : null,
				session.message(), session.status().name(), session.priceSparks(),
				mentor == null ? null : mentor.meetingUrl(), session.createdAt().toString()));
		}
		return views;
	}

	/** O mentor confirma a sessão: o valor retido vai para ele, menos a taxa da plataforma. */
	@PostMapping("/{mentorId}/sessions/{sessionId}/complete")
	ResponseEntity<String> completeSession(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @PathVariable String sessionId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId, "sessionId", sessionId));
		return idempotency.execute(user.id(), "POST", "/api/mentors/{mentorId}/sessions/{sessionId}/complete",
			idempotencyKey, hash, () -> {
				mentorService.completeSession(mentorId, sessionId, user.id().value());
				return new CommandResponse(200, new StatusResponse(sessionId, "COMPLETED"));
			});
	}

	/** Cancelamento: o valor retido volta integral para quem pagou. */
	@PostMapping("/{mentorId}/sessions/{sessionId}/cancel")
	ResponseEntity<String> cancelSession(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String mentorId, @PathVariable String sessionId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("mentorId", mentorId, "sessionId", sessionId));
		return idempotency.execute(user.id(), "POST", "/api/mentors/{mentorId}/sessions/{sessionId}/cancel",
			idempotencyKey, hash, () -> {
				mentorService.cancelSession(mentorId, sessionId, user.id().value());
				return new CommandResponse(200, new StatusResponse(sessionId, "CANCELLED"));
			});
	}

	record MentorSlotView(String id, String startsAt, String endsAt, boolean available) {
	}

	record MySessionView(String id, String mentorId, String mentorUsername, String mentorName, String mentorAvatar,
			String startsAt, String endsAt, String message, String status, Long priceSparks, String meetingUrl,
			String createdAt) {
	}

	record MentorSessionRequest(String slotId, String message) {
	}

	record CreateMentorRequest(List<String> expertise, String price, String banner, String meetingUrl,
			Long sparksPrice) {
	}

	private MentorView mentorView(Mentor m) {
		return new MentorView(m.id(), m.userId(), m.expertise(), m.rating(), m.sessionsCount(), m.price(),
			m.banner(), m.meetingUrl(), m.sparksPrice(), mentorService.nextAvailableSlot(m.id()));
	}

	record CreateSlotRequest(Instant startsAt, Instant endsAt) {
	}

}

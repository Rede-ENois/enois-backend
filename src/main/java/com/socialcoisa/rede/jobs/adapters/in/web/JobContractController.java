package com.socialcoisa.rede.jobs.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.socialcoisa.rede.jobs.application.JobService;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/api/jobs")
class JobContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final JobService jobService;
	private final CursorCodec cursorCodec;

	JobContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			JobService jobService, CursorCodec cursorCodec) {
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.jobService = jobService;
		this.cursorCodec = cursorCodec;
	}

	@PostMapping
	ResponseEntity<JobView> create(@RequestBody CreateJobRequest body,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Job job = jobService.create(body.title(), body.company(), body.logo(), body.location(), body.type(), body.level(),
			body.salary(), body.skills(), user.id().value());
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new JobView(job.id(), job.title(), job.company(), job.logo(), job.location(), job.type(),
				job.level(), job.salary(), job.skills(), job.postedAt().toString(), 0));
	}

	@GetMapping
	PageResponse<JobView> jobs(@RequestParam(required = false) String query,
			@RequestParam(required = false) String type, @RequestParam(required = false) String level,
			@RequestParam(required = false) String location,
			@RequestParam(required = false) List<String> skills,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Job> jobs = jobService.list(query, type, level, location, skills, before, limit);
		var items = jobs.stream()
			.map(j -> new JobView(j.id(), j.title(), j.company(), j.logo(), j.location(), j.type(), j.level(),
				j.salary(), j.skills(), j.postedAt() != null ? j.postedAt().toString() : null, 0))
			.toList();
		String nextCursor = jobs.size() == limit
			? cursorCodec.encode(jobs.get(jobs.size() - 1).postedAt()) : null;
		return new PageResponse<>(items, nextCursor);
	}

	@GetMapping("/{jobId}")
	JobView job(@PathVariable String jobId) {
		Job j = jobService.getById(jobId);
		return new JobView(j.id(), j.title(), j.company(), j.logo(), j.location(), j.type(), j.level(),
			j.salary(), j.skills(), j.postedAt() != null ? j.postedAt().toString() : null, 0);
	}

	@PutMapping("/{jobId}")
	ResponseEntity<JobView> update(@PathVariable String jobId, @RequestBody CreateJobRequest body,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		Job job = jobService.update(jobId, body.title(), body.company(), body.logo(), body.location(), body.type(),
			body.level(), body.salary(), body.skills(), user.id().value());
		return ResponseEntity.ok(new JobView(job.id(), job.title(), job.company(), job.logo(), job.location(),
			job.type(), job.level(), job.salary(), job.skills(), job.postedAt().toString(), 0));
	}

	@DeleteMapping("/{jobId}")
	ResponseEntity<Void> delete(@PathVariable String jobId,
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		jobService.delete(jobId, user.id().value());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{jobId}/applications")
	ResponseEntity<String> apply(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId));
		return idempotency.execute(user.id(), "POST", "/api/jobs/{jobId}/applications", idempotencyKey, hash,
			() -> {
				jobService.apply(jobId, user.id().value());
				return new CommandResponse(201, new StatusResponse(jobId, "APPLIED"));
			});
	}

	@PostMapping("/{jobId}/saves")
	ResponseEntity<String> save(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId));
		return idempotency.execute(user.id(), "POST", "/api/jobs/{jobId}/saves", idempotencyKey, hash,
			() -> {
				jobService.save(jobId, user.id().value());
				return new CommandResponse(200, new StatusResponse(jobId, "SAVED"));
			});
	}

	@DeleteMapping("/{jobId}/saves")
	ResponseEntity<String> removeSave(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId));
		return idempotency.execute(user.id(), "DELETE", "/api/jobs/{jobId}/saves", idempotencyKey, hash,
			() -> {
				jobService.removeSave(jobId, user.id().value());
				return new CommandResponse(200, new StatusResponse(jobId, "UNSAVED"));
			});
	}

	record JobView(String id, String title, String company, String logo, String location, String type, String level,
			String salary, List<String> skills, String postedAt, int matchScore) {
	}

	record CreateJobRequest(String title, String company, String logo, String location, String type, String level,
			String salary, List<String> skills) {
	}

}

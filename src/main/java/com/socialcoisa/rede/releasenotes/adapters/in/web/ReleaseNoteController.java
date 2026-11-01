package com.socialcoisa.rede.releasenotes.adapters.in.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.releasenotes.application.ReleaseNoteService;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNote;
import com.socialcoisa.rede.releasenotes.domain.ReleaseNoteItem;
import com.socialcoisa.rede.shared.web.AdminGuard;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

/**
 * The "what's new" release notes admins publish from the panel.
 *
 * GET /novidades/atual is public and answers 204 when nothing is published. PUT and DELETE live
 * under /admin/**: the interceptor already closes the route, and requireAdmin is the usual defense in
 * depth.
 */
@RestController
class ReleaseNoteController {

	private final ReleaseNoteService releaseNotes;
	private final CurrentUserResolver currentUserResolver;
	private final AdminGuard adminGuard;

	ReleaseNoteController(ReleaseNoteService releaseNotes, CurrentUserResolver currentUserResolver,
			AdminGuard adminGuard) {
		this.releaseNotes = releaseNotes;
		this.currentUserResolver = currentUserResolver;
		this.adminGuard = adminGuard;
	}

	record PublishRequest(List<ReleaseNoteItem> items) {
	}

	record ReleaseNoteView(String id, List<ReleaseNoteItem> items, String publishedAt, String updatedAt) {

		static ReleaseNoteView of(ReleaseNote releaseNote) {
			return new ReleaseNoteView(releaseNote.id(), releaseNote.items(), releaseNote.publishedAt().toString(),
				releaseNote.updatedAt().toString());
		}
	}

	@GetMapping("/novidades/atual")
	ResponseEntity<ReleaseNoteView> current() {
		return releaseNotes.current().map(ReleaseNoteView::of).map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PutMapping("/admin/novidades")
	ReleaseNoteView publish(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody(required = false) PublishRequest request) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		return ReleaseNoteView.of(releaseNotes.publish(request == null ? null : request.items()));
	}

	@DeleteMapping("/admin/novidades")
	ResponseEntity<Void> retire(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		adminGuard.requireAdmin(currentUserResolver.resolve(sessionToken));
		releaseNotes.retire();
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}
}

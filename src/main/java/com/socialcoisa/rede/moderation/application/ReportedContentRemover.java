package com.socialcoisa.rede.moderation.application;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.feed.application.port.in.ModerationRemoveCommentUseCase;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.moderation.domain.Report;

/**
 * Takes the reported content down when a report is confirmed (approval, penalty, ban). Feed posts and
 * comments are removed here; the other targets are handled in their own module by the team.
 */
@Component
public class ReportedContentRemover {

	private final ModerationRemovePostUseCase posts;
	private final ModerationRemoveCommentUseCase comments;

	public ReportedContentRemover(ModerationRemovePostUseCase posts, ModerationRemoveCommentUseCase comments) {
		this.posts = posts;
		this.comments = comments;
	}

	public void remove(Report report, String actorId, String reasonPrefix) {
		String reason = reasonPrefix + ":" + report.reason().name();
		switch (report.targetType()) {
			case POST -> posts.removeAsModerator(actorId, report.targetId(), reason);
			case COMMENT -> comments.removeAsModerator(actorId, report.targetId(), reason);
			default -> {
				// Other targets have no platform-wide removal: the team acts in the target's module.
			}
		}
	}
}

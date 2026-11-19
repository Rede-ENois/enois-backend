package com.socialcoisa.rede.moderation.application;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.colagem.application.port.in.ColagemModerationPort;
import com.socialcoisa.rede.confessionario.application.port.in.ConfessionarioModerationPort;
import com.socialcoisa.rede.desabafos.application.port.in.DesabafosModerationPort;
import com.socialcoisa.rede.desafios.application.port.in.DesafiosModerationPort;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemoveCommentUseCase;
import com.socialcoisa.rede.feed.application.port.in.ModerationRemovePostUseCase;
import com.socialcoisa.rede.murais.application.port.in.MuraisModerationPort;
import com.socialcoisa.rede.moderation.domain.Report;

/**
 * Takes the reported content down when a report is confirmed (approval, penalty, ban). Feed posts and
 * comments are removed here; the other targets are handled in their own module by the team.
 */
@Component
public class ReportedContentRemover {

	private final ModerationRemovePostUseCase posts;
	private final ModerationRemoveCommentUseCase comments;
	private final ColagemModerationPort colagem;
	private final MuraisModerationPort murais;
	private final DesabafosModerationPort desabafos;
	private final ConfessionarioModerationPort confessionario;
	private final DesafiosModerationPort desafios;

	public ReportedContentRemover(ModerationRemovePostUseCase posts, ModerationRemoveCommentUseCase comments,
			ColagemModerationPort colagem, MuraisModerationPort murais, DesabafosModerationPort desabafos,
			ConfessionarioModerationPort confessionario, DesafiosModerationPort desafios) {
		this.posts = posts;
		this.comments = comments;
		this.colagem = colagem;
		this.murais = murais;
		this.desabafos = desabafos;
		this.confessionario = confessionario;
		this.desafios = desafios;
	}

	public void remove(Report report, String actorId, String reasonPrefix) {
		String reason = reasonPrefix + ":" + report.reason().name();
		switch (report.targetType()) {
			case POST -> posts.removeAsModerator(actorId, report.targetId(), reason);
			case COMMENT -> comments.removeAsModerator(actorId, report.targetId(), reason);
			case COLLAGEM_POST -> colagem.removePostAsModerator(actorId, report.targetId(), reason);
			case COLLAGEM_COMMENT -> colagem.removeCommentAsModerator(actorId, report.targetId(), reason);
			case MURAL -> murais.removeMuralAsModerator(actorId, report.targetId(), reason);
			case DESABAFO_POST -> desabafos.removePostAsModerator(actorId, report.targetId(), reason);
			case DESABAFO_COMMENT -> desabafos.removeCommentAsModerator(actorId, report.targetId(), reason);
			case CONFESSIONARIO_POST -> confessionario.removePostAsModerator(actorId, report.targetId(), reason);
			case CONFESSIONARIO_COMMENT -> confessionario.removeCommentAsModerator(actorId, report.targetId(), reason);
			case DESAFIO_POST -> desafios.removeDesafioAsModerator(actorId, report.targetId(), reason);
			case DESAFIO_PARTICIPACAO -> desafios.removeParticipacaoAsModerator(actorId, report.targetId(), reason);
			default -> {
				// Other targets have no platform-wide removal: the team acts in the target's module.
			}
		}
	}
}

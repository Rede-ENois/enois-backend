package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentReader;
import com.socialcoisa.rede.moderation.domain.Report;
import com.socialcoisa.rede.moderation.domain.ReportReason;
import com.socialcoisa.rede.moderation.domain.TargetType;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.security.ActionThrottle;

/**
 * A denúncia sexual em POST é a única que dispara o caminho imediato (quarentena + bot antes da
 * fila comum). Os demais motivos e os demais alvos continuam indo só para a revisão humana — se o
 * gatilho abrir demais, todo post denunciado por qualquer coisa sai do ar sozinho.
 */
@ExtendWith(MockitoExtension.class)
class SexualContentReportWiringTests {

	private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

	@Mock
	private ReportRepository reports;

	@Mock
	private ReportedContentRemover contentRemover;

	@Mock
	private ContentQuarantineReviewService quarantineReview;

	@Mock
	private EmailService emailService;

	@Mock
	private UserProfileRepository profiles;

	@Mock
	private ReportedContentAuthorReader contentAuthors;

	@Mock
	private ReportedContentReader contentReader;

	@Mock
	private SanctionService sanctions;

	@Mock
	private NotificationService notifications;

	@Mock
	private ActionThrottle throttle;

	@Mock
	private ModerationLearningService learning;

	@Mock
	private com.socialcoisa.rede.moderation.application.port.out.CompanyReportReader companyReader;

	@Mock
	private com.socialcoisa.rede.moderation.application.port.out.CompanyRemovalPort companyRemoval;

	@Mock
	private ApplicationEventPublisher events;

	private ModerationService service;

	@BeforeEach
	void setUp() {
		service = new ModerationService(reports, contentRemover, quarantineReview, emailService, "", "https://enois.test",
			profiles, contentAuthors, contentReader, companyReader, companyRemoval, sanctions, notifications,
			throttle, learning, events, 10,
			Clock.fixed(NOW, ZoneOffset.UTC));
		when(reports.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	void denunciaSexualEmPostDisparaOCaminhoImediato() {
		Report saved = service.report("user_denunciante", TargetType.POST, "post_alvo",
			ReportReason.SEXUAL_CONTENT, "a foto e explicita");

		ArgumentCaptor<Object> publicado = ArgumentCaptor.forClass(Object.class);
		verify(events).publishEvent(publicado.capture());
		assertThat(publicado.getValue()).isInstanceOf(SexualContentReportEvent.class);
		SexualContentReportEvent evento = (SexualContentReportEvent) publicado.getValue();
		assertThat(evento.postId()).isEqualTo(saved.targetId()).isEqualTo("post_alvo");
		assertThat(evento.reportDescription()).isEqualTo("a foto e explicita");
	}

	@Test
	void denunciaSexualEmComentarioNaoDispara() {
		service.report("user_denunciante", TargetType.COMMENT, "comment_alvo",
			ReportReason.SEXUAL_CONTENT, "a foto e explicita");

		verify(events, never()).publishEvent(any());
	}

	@Test
	void denunciaDeOutroMotivoEmPostNaoDispara() {
		service.report("user_denunciante", TargetType.POST, "post_alvo", ReportReason.SPAM, "spam");

		verify(events, never()).publishEvent(any());
	}
}

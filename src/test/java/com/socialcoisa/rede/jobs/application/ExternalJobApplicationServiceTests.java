package com.socialcoisa.rede.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.socialcoisa.rede.corporate.application.CorporateProfileService;
import com.socialcoisa.rede.identity.application.EmailService;
import com.socialcoisa.rede.jobs.application.port.out.ExternalJobApplicationRepository;
import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;
import com.socialcoisa.rede.jobs.application.port.out.ResumeFetcher;
import com.socialcoisa.rede.jobs.domain.ExternalJob;
import com.socialcoisa.rede.jobs.domain.ExternalJobApplication;
import com.socialcoisa.rede.jobs.domain.ExternalJobDetails;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Regras da candidatura externa pelo Énóis: confirmação obrigatória, anexo que só é "anexado"
 * quando o arquivo veio, uma candidatura por vaga e nada de e-mail para vaga inativa.
 */
@ExtendWith(MockitoExtension.class)
class ExternalJobApplicationServiceTests {

	private static final String USER = "user_ext_apply";
	private static final String JOB_ID = "ext_Arbeitnow_1";
	private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

	@Mock private ExternalJobRepository externalJobs;
	@Mock private ExternalJobApplicationRepository applications;
	@Mock private UserProfileRepository profiles;
	@Mock private CorporateProfileService corporateProfiles;
	@Mock private ResumeFetcher resumeFetcher;
	@Mock private EmailService emailService;
	@Mock private UserRateLimiter rateLimiter;
	@Mock private PlatformTransactionManager transactionManager;

	private ExternalJobApplicationService service;

	@BeforeEach
	void setUp() {
		// TransactionTemplate com um gerenciador simples: o teste não precisa de banco, só do caminho.
		// Lenient: nos testes que recusam antes da transação, este stub nunca é chamado.
		org.mockito.Mockito.lenient().when(transactionManager.getTransaction(any()))
			.thenReturn(new SimpleTransactionStatus());
		service = new ExternalJobApplicationService(externalJobs, applications, profiles, corporateProfiles,
			resumeFetcher, emailService, rateLimiter, transactionManager, Clock.fixed(NOW, ZoneOffset.UTC),
			"https://enois.ia.br");
	}

	private static ExternalJob job(String applyEmail, ExternalJobDetails details) {
		return new ExternalJob(JOB_ID, "Arbeitnow", "1", "Desenvolvedor Java Sênior", "Acme", "São Paulo, SP",
			null, null, "https://stub.test/vaga/1", NOW, NOW, ExternalJob.FOUND, "https://stub.test/vaga/1",
			applyEmail, null, "Descrição da vaga", null, null, null, false, details);
	}

	private static ExternalJobDetails active() {
		return new ExternalJobDetails(NOW, "FULL_TIME", "SENIOR", "REMOTE", "São Paulo", "SP", "Brasil",
			null, null, null, null, null, NOW, null, null);
	}

	private static UserProfile profile() {
		UserProfile profile = UserProfile.create(USER, "mel", "mel@test.com", "Melissa Monfre", "Dev back-end", NOW);
		profile.updateResume("https://cdn.test/curriculo.pdf", "curriculo.pdf", NOW);
		return profile;
	}

	@Test
	void refusesToSendWithoutExplicitConfirmation() {
		assertThatThrownBy(() -> service.apply(JOB_ID, USER, false, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("Confirme")
			.extracting(e -> ((ApiException) e).code())
			.isEqualTo("CONFIRMATION_REQUIRED");
		verify(emailService, never()).sendHtmlWithAttachment(anyString(), anyString(), anyString(), anyString(),
			anyString(), any(), any(), any(), anyString());
	}

	@Test
	void sendsTheEmailWithTheResumeActuallyAttached() {
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job("vagas@acme.test", active())));
		when(profiles.findById(USER)).thenReturn(Optional.of(profile()));
		when(corporateProfiles.byUser(USER)).thenReturn(Optional.empty());
		when(applications.findByJobAndUser(JOB_ID, USER)).thenReturn(Optional.empty());
		when(resumeFetcher.fetch("https://cdn.test/curriculo.pdf")).thenReturn(Optional.of(
			new ResumeFetcher.FetchedResume(new byte[] { 1, 2, 3 }, "curriculo.pdf", "application/pdf")));
		when(emailService.sendHtmlWithAttachment(eq("vagas@acme.test"), anyString(), anyString(), anyString(),
			eq("mel@test.com"), any(), eq("curriculo.pdf"), eq("application/pdf"), eq("JOB_APPLICATION")))
			.thenReturn(true);

		ExternalJobApplication result = service.apply(JOB_ID, USER, true, null);

		assertThat(result.delivered()).isTrue();
		assertThat(result.resumeAttached()).isTrue();
		assertThat(result.recipientEmail()).isEqualTo("vagas@acme.test");
		assertThat(result.subject()).contains("Desenvolvedor Java Sênior").contains("Acme").contains("[1]");
		// A linha nasce PENDING na reserva e termina SENT depois do envio: dois saves é o fluxo normal.
		verify(applications, org.mockito.Mockito.atLeastOnce()).save(any(ExternalJobApplication.class));
	}

	@Test
	void whenTheResumeCannotBeDownloadedTheEmailGoesWithoutItAndSaysSo() {
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job("vagas@acme.test", active())));
		when(profiles.findById(USER)).thenReturn(Optional.of(profile()));
		when(corporateProfiles.byUser(USER)).thenReturn(Optional.empty());
		when(applications.findByJobAndUser(JOB_ID, USER)).thenReturn(Optional.empty());
		when(resumeFetcher.fetch(anyString())).thenReturn(Optional.empty());
		when(emailService.sendHtmlWithAttachment(anyString(), anyString(), anyString(), anyString(), anyString(),
			isNull(), any(), isNull(), anyString())).thenReturn(true);

		ExternalJobApplication result = service.apply(JOB_ID, USER, true, null);

		assertThat(result.delivered()).isTrue();
		assertThat(result.resumeAttached()).isFalse();
	}

	@Test
	void aSecondApplicationToTheSameJobIsRefused() {
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job("vagas@acme.test", active())));
		when(profiles.findById(USER)).thenReturn(Optional.of(profile()));
		when(corporateProfiles.byUser(USER)).thenReturn(Optional.empty());
		when(applications.findByJobAndUser(JOB_ID, USER)).thenReturn(Optional.of(
			ExternalJobApplication.sent("extapp_1", JOB_ID, USER, "vagas@acme.test", "s", "b", true, "c.pdf", NOW)));

		assertThatThrownBy(() -> service.apply(JOB_ID, USER, true, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("já enviou")
			.extracting(e -> ((ApiException) e).code())
			.isEqualTo("ALREADY_APPLIED_EXTERNAL");
		verify(emailService, never()).sendHtmlWithAttachment(anyString(), anyString(), anyString(), anyString(),
			anyString(), any(), any(), any(), anyString());
	}

	@Test
	void aJobThatIsNoLongerActiveDoesNotReceiveApplications() {
		ExternalJobDetails removed = active().removedAt(NOW);
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job("vagas@acme.test", removed)));

		assertThatThrownBy(() -> service.apply(JOB_ID, USER, true, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("não está mais ativa")
			.extracting(e -> ((ApiException) e).code())
			.isEqualTo("JOB_NOT_ACTIVE");
	}

	@Test
	void aJobWithoutAnApplyEmailCannotBeAppliedToThroughEnois() {
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job(null, active())));

		assertThatThrownBy(() -> service.apply(JOB_ID, USER, true, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("não publica um e-mail")
			.extracting(e -> ((ApiException) e).code())
			.isEqualTo("EXTERNAL_APPLY_UNAVAILABLE");
	}

	@Test
	void theDraftShowsWhatWouldBeSentWithoutSendingAnything() {
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job("vagas@acme.test", active())));
		when(profiles.findById(USER)).thenReturn(Optional.of(profile()));
		when(corporateProfiles.byUser(USER)).thenReturn(Optional.empty());

		var draft = service.draft(JOB_ID, USER);

		assertThat(draft.recipientEmail()).isEqualTo("vagas@acme.test");
		assertThat(draft.resumeAvailable()).isTrue();
		assertThat(draft.body()).contains("Melissa Monfre").contains("Dev back-end")
			.contains("https://enois.ia.br/profissional/mel");
		verify(emailService, never()).sendHtmlWithAttachment(anyString(), anyString(), anyString(), anyString(),
			anyString(), any(), any(), any(), anyString());
	}

	@Test
	void theBodyNeverInventsWhatTheProfileDoesNotSay() {
		UserProfile bare = UserProfile.create(USER, "mel", "mel@test.com", "Melissa", null, NOW);
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job("vagas@acme.test", active())));
		when(profiles.findById(USER)).thenReturn(Optional.of(bare));
		when(corporateProfiles.byUser(USER)).thenReturn(Optional.empty());

		var draft = service.draft(JOB_ID, USER);

		assertThat(draft.body()).doesNotContain("habilidades").doesNotContain("— e acredito");
		assertThat(draft.resumeAvailable()).isFalse();
	}

	@Test
	void theExtraMessageIsEscapedAndPlacedBeforeTheClosing() {
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job("vagas@acme.test", active())));
		when(profiles.findById(USER)).thenReturn(Optional.of(profile()));
		when(corporateProfiles.byUser(USER)).thenReturn(Optional.empty());
		when(applications.findByJobAndUser(JOB_ID, USER)).thenReturn(Optional.empty());
		when(resumeFetcher.fetch(anyString())).thenReturn(Optional.empty());
		when(emailService.sendHtmlWithAttachment(anyString(), anyString(), anyString(), anyString(), anyString(),
			isNull(), any(), isNull(), anyString())).thenReturn(true);

		service.apply(JOB_ID, USER, true, "Tenho 6 anos com <b>Java</b>");

		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(emailService).sendHtmlWithAttachment(anyString(), anyString(), body.capture(), anyString(),
			anyString(), isNull(), any(), isNull(), anyString());
		assertThat(body.getValue()).contains("Tenho 6 anos com &lt;b&gt;Java&lt;/b&gt;");
		assertThat(body.getValue().indexOf("Tenho 6 anos")).isLessThan(body.getValue().indexOf("Fico à disposição"));
	}

	@Test
	void aFailedSendIsRecordedAsFailedAndReportedAsAnError() {
		when(externalJobs.findById(JOB_ID)).thenReturn(Optional.of(job("vagas@acme.test", active())));
		when(profiles.findById(USER)).thenReturn(Optional.of(profile()));
		when(corporateProfiles.byUser(USER)).thenReturn(Optional.empty());
		when(applications.findByJobAndUser(JOB_ID, USER)).thenReturn(Optional.empty());
		when(resumeFetcher.fetch(anyString())).thenReturn(Optional.empty());
		when(emailService.sendHtmlWithAttachment(anyString(), anyString(), anyString(), anyString(), anyString(),
			isNull(), any(), isNull(), anyString())).thenReturn(false);

		assertThatThrownBy(() -> service.apply(JOB_ID, USER, true, null))
			.isInstanceOf(ApiException.class)
			.hasMessageContaining("Não foi possível enviar")
			.extracting(e -> ((ApiException) e).code())
			.isEqualTo("APPLICATION_EMAIL_FAILED");

		ArgumentCaptor<ExternalJobApplication> saved = ArgumentCaptor.forClass(ExternalJobApplication.class);
		verify(applications, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		assertThat(saved.getAllValues()).anyMatch(record -> ExternalJobApplication.FAILED.equals(record.status()));
	}

	@Test
	void theHistoryListsOnlyThisUsersApplications() {
		when(applications.findByUser(USER, 50)).thenReturn(List.of(
			ExternalJobApplication.sent("extapp_1", JOB_ID, USER, "a@b.test", "s", "b", true, "c.pdf", NOW)));

		assertThat(service.mine(USER, 50)).hasSize(1);
	}
}

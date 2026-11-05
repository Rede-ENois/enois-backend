package com.socialcoisa.rede.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;

import com.socialcoisa.rede.ops.application.port.out.EmailLogRepository;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/** Bulk e-mails (blog newsletter, weekly digest) carry RFC 2369 and RFC 8058 one-click headers. */
class EmailServiceListUnsubscribeTests {

	@Test
	void unsubscribeUrlAddsOneClickHeaders() throws Exception {
		JavaMailSender sender = mock(JavaMailSender.class);
		when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
		EmailService email = new EmailService(sender, mock(TemplateEngine.class), "https://app.test",
			"https://api.test", "noreply@test.com", mock(EmailLogRepository.class));

		assertThat(email.sendHtml("reader@test.com", "Assunto", "<p>oi</p>", "Énóis", null,
			"https://app.test/api/notifications/digest/unsubscribe?token=abc", "WEEKLY_DIGEST")).isTrue();

		ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
		verify(sender).send(sent.capture());
		assertThat(sent.getValue().getHeader("List-Unsubscribe", null))
			.isEqualTo("<https://app.test/api/notifications/digest/unsubscribe?token=abc>");
		assertThat(sent.getValue().getHeader("List-Unsubscribe-Post", null)).isEqualTo("List-Unsubscribe=One-Click");
	}
}

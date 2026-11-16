package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.socialcoisa.rede.shared.audit.AuditTrail;

/** Trilha das recusas administrativas: o conteúdo da linha e o comportamento sob falha da trilha. */
class AdminAuthorityAuditTests {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void recusaDaContaGravaRotaMotivoStatusEIp() {
		AuditTrail trail = mock(AuditTrail.class);
		AdminAuthorityAudit audit = new AdminAuthorityAudit(trail, CLOCK, 20, 10);

		audit.denied("user-1", "GET", "/admin/reports/1234567890abcdef", 403,
			AdminAuthorityAudit.ADMIN_ONLY, "203.0.113.10");

		ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
		verify(trail).record(eq("user-1"), eq(AdminAuthorityAudit.ACTION), eq("/admin/reports/{id}"),
			metadata.capture());
		assertThat(metadata.getValue())
			.contains("\"method\":\"GET\"")
			.contains("\"status\":403")
			.contains("\"reason\":\"ADMIN_ONLY\"")
			.contains("\"clientIp\":\"203.0.113.10\"");
		assertThat(audit.summary().total()).isEqualTo(1);
	}

	@Test
	void semSessaoOAtorEAAnonimo() {
		AuditTrail trail = mock(AuditTrail.class);
		AdminAuthorityAudit audit = new AdminAuthorityAudit(trail, CLOCK, 2, 10);

		audit.denied(null, "POST", "/moderation/posts/x/remove", 401,
			AdminAuthorityAudit.SESSION_MISSING, null);

		verify(trail).record(eq("anonymous"), eq(AdminAuthorityAudit.ACTION), eq("/moderation/posts/x/remove"),
			anyString());
		assertThat(audit.suspects()).isEmpty();
	}

	@Test
	void aposOLimiarAContaEntraNaListaDeSuspeitosUMAVez() {
		AuditTrail trail = mock(AuditTrail.class);
		AdminAuthorityAudit audit = new AdminAuthorityAudit(trail, CLOCK, 3, 10);

		for (int i = 0; i < 5; i++) {
			audit.denied("user-2", "GET", "/admin/support", 403, AdminAuthorityAudit.MODERATOR_ONLY, "198.51.100.7");
		}

		assertThat(audit.suspects()).singleElement().satisfies(suspect -> {
			assertThat(suspect.userId()).isEqualTo("user-2");
			assertThat(suspect.denialsInWindow()).isEqualTo(5);
			assertThat(suspect.totalDenials()).isEqualTo(5);
			assertThat(suspect.lastDenial().reason()).isEqualTo(AdminAuthorityAudit.MODERATOR_ONLY);
		});
		assertThat(audit.summary().alerts()).isEqualTo(1);
		assertThat(audit.summary().suspects()).isEqualTo(1);
		assertThat(audit.summary().lastDenialAt()).isEqualTo(CLOCK.instant().toString());
	}

	@Test
	void falhaDaTrilhaNaoDerrubaADecisao() {
		AuditTrail trail = mock(AuditTrail.class);
		doThrow(new IllegalStateException("banco fora")).when(trail)
			.record(anyString(), anyString(), anyString(), any());
		AdminAuthorityAudit audit = new AdminAuthorityAudit(trail, CLOCK, 20, 10);

		assertThatCode(() -> audit.denied("user-3", "GET", "/admin/x", 403, "ADMIN_ONLY", "127.0.0.1"))
			.doesNotThrowAnyException();
		assertThat(audit.summary().total()).isEqualTo(1);
	}

	@Test
	void rotaNaoCarregaIdVariavel() {
		assertThat(AdminAuthorityAudit.routeOf("/admin/users/6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f/ips"))
			.isEqualTo("/admin/users/{id}/ips");
		assertThat(AdminAuthorityAudit.routeOf("")).isEqualTo("/");
		assertThat(AdminAuthorityAudit.routeOf("/admin/reports")).isEqualTo("/admin/reports");
	}

	@Test
	void metadadoEscapaAspasNaoQuebraOJson() {
		AuditTrail trail = mock(AuditTrail.class);
		AdminAuthorityAudit audit = new AdminAuthorityAudit(trail, CLOCK, 20, 10);
		ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);

		audit.record("user-4", "ADMIN", java.util.Map.of("route", "/a\"b"));

		verify(trail).record(eq("user-4"), eq(AdminAuthorityAudit.ACTION), eq("ADMIN"), metadata.capture());
		assertThat(metadata.getValue()).isEqualTo("{\"route\":\"/a\\\"b\"}");
	}
}
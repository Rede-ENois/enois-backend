package com.socialcoisa.rede.moderation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.socialcoisa.rede.identity.application.port.out.AuthSessionRepository;
import com.socialcoisa.rede.moderation.application.port.out.ModerationVoteRepository;
import com.socialcoisa.rede.moderation.application.port.out.ModeratorApplicationRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportRepository;
import com.socialcoisa.rede.moderation.application.port.out.ReportedContentAuthorReader;
import com.socialcoisa.rede.moderation.application.port.out.SanctionRepository;
import com.socialcoisa.rede.moderation.domain.SanctionType;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Testa o fluxo de banimento com bloqueio de IPs: bane o usuario e bloqueia todos os IPs
 * conhecidos numa operacao so.
 */
class BanWithIpsTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC);

	private IpBlockService ipBlockService;
	private ModeratorService service;

	@BeforeEach
	void setUp() {
		ipBlockService = mock(IpBlockService.class);

		// Spy no ModeratorService: o sanctionUser precisa de muitas dependencias que nao importam
		// para o teste do banWithIps. Stubamos o sanctionUser diretamente.
		service = Mockito.spy(new ModeratorService(
			mock(ModeratorApplicationRepository.class),
			mock(ModerationVoteRepository.class),
			mock(ReportRepository.class),
			mock(ModerationService.class),
			mock(SanctionService.class),
			mock(SanctionRepository.class),
			mock(ReputationService.class),
			mock(ReportedContentAuthorReader.class),
			mock(NotificationService.class),
			mock(UserProfileRepository.class),
			mock(AuthSessionRepository.class),
			mock(com.socialcoisa.rede.feed.application.port.in.PurgeAuthorContentUseCase.class),
			mock(com.socialcoisa.rede.shared.sanctions.SanctionGateReader.class),
			ipBlockService,
			CLOCK, 200, 3
		));
	}

	@Test
	void baneUsuarioEBloqueiaOsDoisIps() {
		var sanction = new ModeratorService.SanctionResult("usr_1", "spammer", "PERMABAN", "ACTIVE");
		doReturn(sanction).when(service).sanctionUser("admin_1", "spammer", SanctionType.PERMABAN,
			"Spam repetido na timeline");

		when(ipBlockService.ipsOfUser("spammer")).thenReturn(List.of(
			new IpBlockService.UserIpView("1.2.3.4", null),
			new IpBlockService.UserIpView("5.6.7.8", null)
		));
		when(ipBlockService.block(eq("admin_1"), eq("1.2.3.4"), anyString()))
			.thenReturn(new IpBlockService.BlockedIpView("ipblk_1", "1.2.3.4", "motivo", "admin_1", "2026-11-05T12:00:00Z"));
		when(ipBlockService.block(eq("admin_1"), eq("5.6.7.8"), anyString()))
			.thenReturn(new IpBlockService.BlockedIpView("ipblk_2", "5.6.7.8", "motivo", "admin_1", "2026-11-05T12:00:00Z"));

		var result = service.banWithIps("admin_1", "spammer", "Spam repetido na timeline");

		assertThat(result.sanction().status()).isEqualTo("ACTIVE");
		assertThat(result.sanction().type()).isEqualTo("PERMABAN");
		assertThat(result.blockedIps()).containsExactly("1.2.3.4", "5.6.7.8");
		assertThat(result.alreadyBlockedIps()).isEmpty();
	}

	@Test
	void ipJaBloqueadoVaiParaListaSeparada() {
		var sanction = new ModeratorService.SanctionResult("usr_1", "spammer", "PERMABAN", "ACTIVE");
		doReturn(sanction).when(service).sanctionUser("admin_1", "spammer", SanctionType.PERMABAN,
			"Conta de spam");

		when(ipBlockService.ipsOfUser("spammer")).thenReturn(List.of(
			new IpBlockService.UserIpView("1.2.3.4", null),
			new IpBlockService.UserIpView("9.9.9.9", null)
		));
		when(ipBlockService.block(eq("admin_1"), eq("1.2.3.4"), anyString()))
			.thenReturn(new IpBlockService.BlockedIpView("ipblk_1", "1.2.3.4", "motivo", "admin_1", "2026-11-05T12:00:00Z"));
		when(ipBlockService.block(eq("admin_1"), eq("9.9.9.9"), anyString()))
			.thenThrow(new ApiException(409, "IP_ALREADY_BLOCKED", "Ja bloqueado"));

		var result = service.banWithIps("admin_1", "spammer", "Conta de spam");

		assertThat(result.blockedIps()).containsExactly("1.2.3.4");
		assertThat(result.alreadyBlockedIps()).containsExactly("9.9.9.9");
	}

	@Test
	void semIpsConhecidosApenaBane() {
		var sanction = new ModeratorService.SanctionResult("usr_1", "fantasma", "PERMABAN", "ACTIVE");
		doReturn(sanction).when(service).sanctionUser("admin_1", "fantasma", SanctionType.PERMABAN,
			"Multiplas contas de spam");

		when(ipBlockService.ipsOfUser("fantasma")).thenReturn(List.of());

		var result = service.banWithIps("admin_1", "fantasma", "Multiplas contas de spam");

		assertThat(result.sanction().username()).isEqualTo("fantasma");
		assertThat(result.blockedIps()).isEmpty();
		assertThat(result.alreadyBlockedIps()).isEmpty();
		verify(ipBlockService, never()).block(anyString(), anyString(), anyString());
	}

	@Test
	void usuarioJaBanidoContinuaBloqueandoIps() {
		var sanction = new ModeratorService.SanctionResult("usr_1", "reincidente", "PERMABAN", "ALREADY_BANNED");
		doReturn(sanction).when(service).sanctionUser("admin_1", "reincidente", SanctionType.PERMABAN,
			"Voltou com o mesmo IP");

		when(ipBlockService.ipsOfUser("reincidente")).thenReturn(List.of(
			new IpBlockService.UserIpView("10.0.0.1", null)
		));
		when(ipBlockService.block(eq("admin_1"), eq("10.0.0.1"), anyString()))
			.thenReturn(new IpBlockService.BlockedIpView("ipblk_3", "10.0.0.1", "motivo", "admin_1", "2026-11-05T12:00:00Z"));

		var result = service.banWithIps("admin_1", "reincidente", "Voltou com o mesmo IP");

		assertThat(result.sanction().status()).isEqualTo("ALREADY_BANNED");
		assertThat(result.blockedIps()).containsExactly("10.0.0.1");
	}
}

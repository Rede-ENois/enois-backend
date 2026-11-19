package com.socialcoisa.rede.messaging.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.profile.application.port.out.AccountIdentity;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.age.AgePolicyReader;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.ApiException;

/** Creating a large group reads blocks, ages and connections once for the whole set, not per person. */
class GroupCreationQueryCountTests {

	private final AgePolicyReader agePolicy = mock(AgePolicyReader.class);
	private final ConnectionRepository connections = mock(ConnectionRepository.class);
	private final BlockedUsersReader blockedUsers = mock(BlockedUsersReader.class);
	private final SanctionGateReader sanctions = mock(SanctionGateReader.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final MessagingService service = new MessagingService(mock(MessagingRepository.class),
		mock(com.socialcoisa.rede.notifications.application.NotificationService.class), Clock.systemUTC(),
		mock(com.socialcoisa.rede.shared.security.UserRateLimiter.class),
		mock(com.socialcoisa.rede.shared.security.BehavioralTimingGuard.class), mock(AgeGate.class),
		mock(com.socialcoisa.rede.shared.sanctions.SanctionGate.class),
		new ConversationReachPolicy(agePolicy, connections, blockedUsers, sanctions, profiles),
		mock(com.socialcoisa.rede.corporate.application.CorporateProfileService.class),
		mock(com.socialcoisa.rede.coin.application.CoinService.class),
		mock(org.springframework.context.ApplicationEventPublisher.class), new ConversationViewers(Clock.systemUTC()));

	private final List<String> members = IntStream.range(0, 99).mapToObj(i -> "member-" + i).toList();

	@org.junit.jupiter.api.BeforeEach
	void everyMemberExists() {
		// A política passou a exigir conta existente: o cenário aqui é de grupo válido.
		when(profiles.identitiesOf(any())).thenReturn(
			members.stream().map(id -> new AccountIdentity(id, id, id)).toList());
	}

	@Test
	void largeGroupIsCheckedWithOneReadPerKind() {
		when(blockedUsers.hiddenIdsFor("creator")).thenReturn(Set.of());
		when(agePolicy.minorIds(any())).thenReturn(Set.of("member-7"));
		when(connections.findAcceptedAmong("creator", Set.of("member-7"))).thenReturn(Set.of("member-7"));

		service.createConversation("GROUP", "Grupo", members, "creator");

		verify(blockedUsers, times(1)).hiddenIdsFor(anyString());
		verify(agePolicy, times(1)).minorIds(any());
		verify(agePolicy, never()).isMinor(anyString());
		verify(connections, never()).existsAcceptedConnection(anyString(), anyString());
		verify(connections, never()).findRequests(anyString(), any());
	}

	@Test
	void unconnectedMinorIsStillRefused() {
		when(blockedUsers.hiddenIdsFor("creator")).thenReturn(Set.of());
		when(agePolicy.minorIds(any())).thenReturn(Set.of("member-3"));

		assertThatThrownBy(() -> service.createConversation("GROUP", "Grupo", members, "creator"))
			.isInstanceOf(ApiException.class).hasMessageContaining("menores");
	}

	@Test
	void blockedMemberIsStillRefused() {
		when(blockedUsers.hiddenIdsFor("creator")).thenReturn(Set.of("member-42"));

		assertThatThrownBy(() -> service.createConversation("GROUP", "Grupo", members, "creator"))
			.isInstanceOf(ApiException.class).hasMessageContaining("conversar");
	}
}

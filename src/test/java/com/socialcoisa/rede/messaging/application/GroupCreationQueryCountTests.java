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
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.age.AgePolicyReader;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.ApiException;

/** Creating a large group reads blocks, ages and connections once for the whole set, not per person. */
class GroupCreationQueryCountTests {

	private final AgePolicyReader agePolicy = mock(AgePolicyReader.class);
	private final ConnectionRepository connections = mock(ConnectionRepository.class);
	private final BlockedUsersReader blockedUsers = mock(BlockedUsersReader.class);
	private final MessagingService service = new MessagingService(mock(MessagingRepository.class),
		mock(com.socialcoisa.rede.notifications.application.NotificationService.class), Clock.systemUTC(),
		mock(com.socialcoisa.rede.shared.security.UserRateLimiter.class),
		mock(com.socialcoisa.rede.shared.security.BehavioralTimingGuard.class), agePolicy, mock(AgeGate.class),
		mock(com.socialcoisa.rede.shared.sanctions.SanctionGate.class), connections, blockedUsers,
		mock(com.socialcoisa.rede.corporate.application.CorporateProfileService.class),
		mock(com.socialcoisa.rede.coin.application.CoinService.class));

	private final List<String> members = IntStream.range(0, 99).mapToObj(i -> "member-" + i).toList();

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

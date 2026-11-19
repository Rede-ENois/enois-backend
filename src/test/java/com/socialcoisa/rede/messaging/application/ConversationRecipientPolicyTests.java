package com.socialcoisa.rede.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.profile.application.port.out.AccountIdentity;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.age.AgePolicyReader;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.sanctions.SanctionGate;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Destinatário validado antes de qualquer escrita: id inexistente, conta excluída, conta banida,
 * bloqueio nos dois sentidos e regra de menor valem para todo tipo de conversa e para todas as
 * rotas que a abrem.
 */
class ConversationRecipientPolicyTests {

	private static final String ACTOR = "user_actor";
	private static final String OTHER = "user_other";

	private final AgePolicyReader agePolicy = mock(AgePolicyReader.class);
	private final ConnectionRepository connections = mock(ConnectionRepository.class);
	private final BlockedUsersReader blockedUsers = mock(BlockedUsersReader.class);
	private final SanctionGateReader sanctions = mock(SanctionGateReader.class);
	private final UserProfileRepository profiles = mock(UserProfileRepository.class);
	private final MessagingRepository repository = mock(MessagingRepository.class);

	private final ConversationReachPolicy policy =
		new ConversationReachPolicy(agePolicy, connections, blockedUsers, sanctions, profiles);

	/** Ids que existem neste cenário; tudo o mais é conta inexistente. */
	private void existing(String... ids) {
		List<AccountIdentity> identities = new ArrayList<>();
		for (String id : ids) {
			identities.add(new AccountIdentity(id, "Pessoa " + id, id.replace("user_", "")));
		}
		when(profiles.identitiesOf(any())).thenReturn(identities);
	}

	private MessagingService service() {
		return new MessagingService(repository,
			mock(com.socialcoisa.rede.notifications.application.NotificationService.class), Clock.systemUTC(),
			mock(com.socialcoisa.rede.shared.security.UserRateLimiter.class),
			mock(com.socialcoisa.rede.shared.security.BehavioralTimingGuard.class), mock(AgeGate.class),
			mock(SanctionGate.class), policy,
			mock(com.socialcoisa.rede.corporate.application.CorporateProfileService.class),
			mock(com.socialcoisa.rede.coin.application.CoinService.class),
			mock(org.springframework.context.ApplicationEventPublisher.class),
			new ConversationViewers(Clock.systemUTC()));
	}

	@Test
	void unknownRecipientIsRefusedWithoutWriting() {
		existing(ACTOR, OTHER);

		assertThatThrownBy(() -> service().createConversation("DIRECT", null,
			List.of("user_missing"), ACTOR))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.status()).isEqualTo(404);
				assertThat(error.code()).isEqualTo("USER_NOT_FOUND");
			});
		verifyNoInteractions(repository);
	}

	@Test
	void malformedRecipientNeverReachesTheDatabase() {
		assertThatThrownBy(() -> policy.resolveRecipient("DIRECT", ACTOR, "user_other/../conversation_x"))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.status()).isEqualTo(400);
				assertThat(error.code()).isEqualTo("INVALID_RECIPIENT");
			});
		verifyNoInteractions(profiles, sanctions, blockedUsers);
	}

	@Test
	void emptyRecipientIsRefused() {
		assertThatThrownBy(() -> policy.resolveRecipient("DIRECT", ACTOR, "  "))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("INVALID_RECIPIENT"));
		assertThatThrownBy(() -> policy.resolveRecipient("DIRECT", ACTOR, null))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("INVALID_RECIPIENT"));
	}

	@Test
	void deletedAccountIsRefusedAsInexistent() {
		when(profiles.identitiesOf(any())).thenReturn(List.of(
			new AccountIdentity(ACTOR, "Autor", "actor"),
			new AccountIdentity(OTHER, "Conta removida", "removed-abc123")));

		assertThatThrownBy(() -> policy.resolveRecipient("DIRECT", ACTOR, OTHER))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("USER_NOT_FOUND"));
	}

	@Test
	void bannedRecipientIsRefused() {
		existing(ACTOR, OTHER);
		when(sanctions.bannedAmong(any())).thenReturn(Set.of(OTHER));

		assertThatThrownBy(() -> policy.resolveRecipient("DIRECT", ACTOR, OTHER))
			.isInstanceOfSatisfying(ApiException.class, error -> {
				assertThat(error.status()).isEqualTo(403);
				assertThat(error.code()).isEqualTo("USER_UNAVAILABLE");
			});
	}

	@Test
	void bannedMemberRefusesTheWholeGroup() {
		existing(ACTOR, OTHER, "user_third");
		when(sanctions.bannedAmong(any())).thenReturn(Set.of("user_third"));

		assertThatThrownBy(() -> policy.resolveParticipants("GROUP", ACTOR, List.of(OTHER, "user_third")))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("USER_UNAVAILABLE"));
	}

	@Test
	void blockInEitherDirectionRefusesTheDirectConversation() {
		existing(ACTOR, OTHER);
		when(blockedUsers.blockedIdsFor(ACTOR)).thenReturn(Set.of(OTHER));

		assertThatThrownBy(() -> policy.resolveRecipient("DIRECT", ACTOR, OTHER))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("USER_BLOCKED"));
		assertThatThrownBy(() -> policy.requireMayOpenPair("BUSINESS_DIRECT", ACTOR, OTHER))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("USER_BLOCKED"));
		assertThatThrownBy(() -> policy.requireMayOpenPair("FREELANCE", ACTOR, OTHER))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("USER_BLOCKED"));
	}

	@Test
	void groupDoesNotSilenceABlockBetweenMembers() {
		existing(ACTOR, OTHER);
		when(blockedUsers.hiddenIdsFor(ACTOR)).thenReturn(Set.of());

		// O bloqueio que importa para o criador é o dele; o par bloqueado entre si não impede o grupo.
		assertThat(policy.resolveParticipants("GROUP", ACTOR, List.of(OTHER, ACTOR)))
			.containsExactly(OTHER, ACTOR);
		verify(blockedUsers, never()).blockedIdsFor(anyString());
	}

	@Test
	void groupRefusesSomeoneTheCreatorHides() {
		existing(ACTOR, OTHER);
		when(blockedUsers.hiddenIdsFor(ACTOR)).thenReturn(Set.of(OTHER));

		assertThatThrownBy(() -> policy.resolveParticipants("GROUP", ACTOR, List.of(OTHER)))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("USER_BLOCKED"));
	}

	@Test
	void minorIsReachableOnlyWithAnAcceptedConnection() {
		existing(ACTOR, OTHER);
		when(agePolicy.minorIds(List.of(OTHER))).thenReturn(Set.of(OTHER));
		when(connections.findAcceptedAmong(ACTOR, Set.of(OTHER))).thenReturn(Set.of());

		assertThatThrownBy(() -> policy.resolveRecipient("DIRECT", ACTOR, OTHER))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("MINOR_DM_RESTRICTED"));

		when(connections.findAcceptedAmong(ACTOR, Set.of(OTHER))).thenReturn(Set.of(OTHER));
		assertThat(policy.resolveRecipient("DIRECT", ACTOR, OTHER)).isEqualTo(OTHER);
	}

	@Test
	void jobAndServiceKeepWorkingForAMinorWithoutConnection() {
		existing(ACTOR, OTHER);
		when(agePolicy.minorIds(List.of(OTHER))).thenReturn(Set.of(OTHER));

		assertThatCode(() -> policy.requireMayOpenPair("JOB", ACTOR, OTHER)).doesNotThrowAnyException();
		assertThatCode(() -> policy.requireMayOpenPair("SERVICE", ACTOR, OTHER)).doesNotThrowAnyException();
	}

	@Test
	void typeOutsideTheConversationSetIsRefused() {
		existing(ACTOR, OTHER);

		assertThatThrownBy(() -> policy.resolveRecipient("CHANNEL_PUBLIC", ACTOR, OTHER))
			.isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("INVALID_CONVERSATION_KIND"));
	}

	@Test
	void existingDirectThreadIsReusedInsteadOfDuplicated() {
		existing(ACTOR, OTHER);
		Conversation existing = Conversation.create("conversation_pair", "DIRECT", null, ACTOR,
			java.time.Instant.parse("2026-11-01T00:00:00Z"));
		when(repository.findPairByKind(ACTOR, OTHER, "DIRECT")).thenReturn(Optional.of(existing));

		assertThat(service().createConversation("DIRECT", null, List.of(OTHER), ACTOR)).isSameAs(existing);
		verify(repository, never()).saveConversation(any());
	}

	@Test
	void validRecipientsAreReadAndCheckedOnceForTheWholeSet() {
		existing(ACTOR, OTHER, "user_third");
		when(blockedUsers.hiddenIdsFor(anyString())).thenReturn(Set.of());
		when(agePolicy.minorIds(any())).thenReturn(Set.of());
		when(sanctions.bannedAmong(any())).thenReturn(Set.of());

		Conversation created = service().createConversation("GROUP", "Grupo",
			List.of(OTHER, " user_third ", OTHER), ACTOR);

		assertThat(created.kind()).isEqualTo("GROUP");
		verify(profiles, times(1)).identitiesOf(any());
		verify(blockedUsers, times(1)).hiddenIdsFor(ACTOR);
		verify(agePolicy, times(1)).minorIds(any());
		verify(repository, times(1)).saveConversation(any());
	}
}
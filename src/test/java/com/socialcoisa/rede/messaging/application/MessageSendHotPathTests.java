package com.socialcoisa.rede.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.socialcoisa.rede.messaging.application.port.out.MessagingRepository;
import com.socialcoisa.rede.messaging.domain.Conversation;
import com.socialcoisa.rede.messaging.domain.ConversationParticipant;
import com.socialcoisa.rede.messaging.domain.Message;
import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.age.AgePolicyReader;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.security.BehavioralTimingGuard;
import com.socialcoisa.rede.shared.security.UserRateLimiter;

/**
 * O envio de mensagem e a rota mais usada do modulo: ela roda em CADA mensagem e nao pode fazer
 * varredura nem pergunta duplicada.
 *
 * <p>Antes, um envio lia a lista de participantes TRES vezes (checagem de participante, alcance e
 * fan-out de notificacoes), perguntava bloqueio destinatario por destinatario (duas idas ao banco
 * por pessoa) e ainda relia o participante so para gravar o "lido". O bloco de bloqueio tambem nao
 * existia no caminho de envio: quem bloqueou o outro continuava alcancavel.</p>
 */
class MessageSendHotPathTests {

	private static final String CONVERSATION_ID = "conversation_hot_path";
	private static final String SENDER_ID = "sender_hot_path";
	private static final String OTHER_ID = "other_hot_path";
	private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

	private final MessagingRepository repository = mock(MessagingRepository.class);
	private final NotificationService notifications = mock(NotificationService.class);
	private final UserRateLimiter rateLimiter = mock(UserRateLimiter.class);
	private final BehavioralTimingGuard timingGuard = mock(BehavioralTimingGuard.class);
	private final AgeGate ageGate = mock(AgeGate.class);
	private final com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate =
		mock(com.socialcoisa.rede.shared.sanctions.SanctionGate.class);
	private final AgePolicyReader agePolicy = mock(AgePolicyReader.class);
	private final ConnectionRepository connections = mock(ConnectionRepository.class);
	private final BlockedUsersReader blockedUsers = mock(BlockedUsersReader.class);
	private final com.socialcoisa.rede.corporate.application.CorporateProfileService corporateProfiles =
		mock(com.socialcoisa.rede.corporate.application.CorporateProfileService.class);
	private final com.socialcoisa.rede.coin.application.CoinService coins =
		mock(com.socialcoisa.rede.coin.application.CoinService.class);
	private final org.springframework.context.ApplicationEventPublisher events =
		mock(org.springframework.context.ApplicationEventPublisher.class);

	private MessagingService service;

	@BeforeEach
	void setUp() {
		service = new MessagingService(repository, notifications, Clock.fixed(NOW, ZoneOffset.UTC), rateLimiter,
			timingGuard, ageGate, sanctionGate, new ConversationReachPolicy(agePolicy, connections, blockedUsers),
			corporateProfiles, coins, events, new ConversationViewers(Clock.fixed(NOW, ZoneOffset.UTC)));

		when(repository.findConversationById(CONVERSATION_ID))
			.thenReturn(Optional.of(Conversation.create(CONVERSATION_ID, "DIRECT", null, SENDER_ID, NOW)));
		when(repository.findParticipantsByConversation(CONVERSATION_ID)).thenReturn(List.of(
			ConversationParticipant.create(CONVERSATION_ID, SENDER_ID, NOW),
			ConversationParticipant.create(CONVERSATION_ID, OTHER_ID, NOW)));
		when(blockedUsers.blockedIdsFor(SENDER_ID)).thenReturn(Set.of());
		when(agePolicy.minorIds(any())).thenReturn(Set.of());
		when(repository.findLastMessage(CONVERSATION_ID)).thenReturn(Optional.empty());
		when(repository.findRecentDuplicate(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
	}

	@Test
	void sendReadsTheParticipantsOnce() {
		service.sendMessage(CONVERSATION_ID, SENDER_ID, "oi", List.of(), false, null);

		// A lista de participantes serve a checagem de participante, o alcance, as notificacoes e o
		// aviso de mudanca. Uma segunda leitura custava uma ida ao banco por mensagem.
		verify(repository, times(1)).findParticipantsByConversation(CONVERSATION_ID);
	}

	@Test
	void sendChecksBlocksInOneReadForTheWholeSet() {
		service.sendMessage(CONVERSATION_ID, SENDER_ID, "oi", List.of(), false, null);

		// Um conjunto inteiro em UMA leitura: perguntar destinatario por destinatario custava duas
		// idas ao banco por pessoa.
		verify(blockedUsers, times(1)).blockedIdsFor(SENDER_ID);
		verify(blockedUsers, never()).hiddenIdsFor(anyString());
		verify(blockedUsers, never()).blockedBetween(anyString(), anyString());
	}

	@Test
	void blockedRecipientCannotBeReached() {
		when(blockedUsers.blockedIdsFor(SENDER_ID)).thenReturn(Set.of(OTHER_ID));

		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> service.sendMessage(CONVERSATION_ID, SENDER_ID, "oi", List.of(), false, null))
			.isInstanceOf(com.socialcoisa.rede.shared.web.ApiException.class)
			.hasMessageContaining("conversar");

		verify(repository, never()).saveMessage(any(Message.class));
	}

	@Test
	void sendMarksTheConversationReadWithTheInstantOfTheMessage() {
		service.sendMessage(CONVERSATION_ID, SENDER_ID, "oi", List.of(), false, null);

		ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
		verify(repository).markRead(eq(CONVERSATION_ID), eq(SENDER_ID), when.capture());
		// O read usa o instante da mensagem: o desempate de mesmo segundo faz a mensagem nascer a
		// frente do relogio, e um lastReadAt menor deixava a propria mensagem contando como nao
		// lida para quem acabou de enviar.
		assertThat(when.getValue()).isEqualTo(NOW);
	}

	@Test
	void e2eSendAlsoMarksReadWithTheInstantOfTheMessage() {
		service.sendE2eMessage(CONVERSATION_ID, SENDER_ID, "cifra", "aXYxMjM=",
			"{\"other\":\"chave\"}", List.of(), false, null);

		ArgumentCaptor<Instant> when = ArgumentCaptor.forClass(Instant.class);
		verify(repository).markRead(eq(CONVERSATION_ID), eq(SENDER_ID), when.capture());
		assertThat(when.getValue()).isEqualTo(NOW);
	}

	@Test
	void repeatedE2eContentReturnsTheSameRowInsteadOfDuplicating() {
		// A mesma cifra reenviada (duplo clique no botao de cifrar) devolve a linha ja criada.
		when(repository.findRecentDuplicate(anyString(), anyString(), anyString(), any()))
			.thenReturn(Optional.of(Message.createE2e("message_existing", CONVERSATION_ID, SENDER_ID, "cifra",
				"aXYxMjM=", List.of(), false, NOW)));

		Message result = service.sendE2eMessage(CONVERSATION_ID, SENDER_ID, "cifra", "aXYxMjM=",
			"{\"other\":\"chave\"}", List.of(), false, null);

		assertThat(result.id()).isEqualTo("message_existing");
		verify(repository, never()).saveMessage(any(Message.class));
	}

	@Test
	void e2eWithDifferentMediaIsNotTheSameRow() {
		// Mesma cifra, mas outro conjunto de midias: nao e a mesma mensagem, entao nao pode voltar
		// a linha antiga — o reenvio traria o anexo errado.
		when(repository.findRecentDuplicate(anyString(), anyString(), anyString(), any()))
			.thenReturn(Optional.of(Message.createE2e("message_com_midia", CONVERSATION_ID, SENDER_ID, "cifra",
				"aXYxMjM=", List.of("midia-antiga"), false, NOW)));

		Message result = service.sendE2eMessage(CONVERSATION_ID, SENDER_ID, "cifra", "aXYxMjM=",
			"{\"other\":\"chave\"}", List.of("midia-nova"), false, null);

		assertThat(result.id()).isNotEqualTo("message_com_midia");
		verify(repository).saveMessage(any(Message.class));
	}

	@Test
	void duplicateWindowIsTwoMinutes() {
		service.sendMessage(CONVERSATION_ID, SENDER_ID, "oi", List.of(), false, null);

		ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
		verify(repository).findRecentDuplicate(eq(CONVERSATION_ID), eq(SENDER_ID), eq("oi"), since.capture());
		// O comentario do codigo prometia 2 minutos e a janela eram 10 segundos: um retry de
		// transporte mais lento que isso criava a mensagem de novo e notificava duas vezes.
		assertThat(since.getValue()).isEqualTo(NOW.minusSeconds(120));
	}
}

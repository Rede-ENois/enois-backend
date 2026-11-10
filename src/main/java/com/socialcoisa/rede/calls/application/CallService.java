package com.socialcoisa.rede.calls.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.calls.application.port.out.CallRepository;
import com.socialcoisa.rede.calls.application.port.out.MeetingTokenIssuer;
import com.socialcoisa.rede.calls.domain.CallKind;
import com.socialcoisa.rede.calls.domain.CallModality;
import com.socialcoisa.rede.calls.domain.CallParticipant;
import com.socialcoisa.rede.calls.domain.CallParticipantState;
import com.socialcoisa.rede.calls.domain.CallSession;
import com.socialcoisa.rede.calls.domain.CallState;
import com.socialcoisa.rede.groups.domain.GroupTopic;
import com.socialcoisa.rede.groups.domain.GroupTopicStatus;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.age.AgeGate;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Chamadas de áudio e vídeo, 1:1 e "Tamo junto" (grupo).
 *
 * <p>O servidor é quem decide: quem pode começar, quem pode entrar, em que estado a chamada está
 * e por quanto tempo. O cliente só pede e recebe o resultado; nenhuma regra é inferida na tela.
 * Transições concorrentes são condicionais no banco, então dois cliques simultâneos não deixam a
 * chamada em dois estados nem contam a mesma pessoa duas vezes.
 *
 * <p>Regras de produto embutidas aqui:
 * <ul>
 * <li>1:1 toca ({@link CallState#RINGING}) com prazo curto e vira "não atendida" sozinha.</li>
 * <li>Grupo "Tamo junto" NÃO toca ninguém: nasce "ao vivo" com quem abriu já dentro e some um
 * minuto depois de a última pessoa sair (a tolerância é o que deixa recarregar a página).</li>
 * <li>Cada tópico do grupo tem a própria sala de vídeo "Tamo junto"; a sala geral é outra.</li>
 * <li>Sair do grupo não encerra a chamada para os outros; no 1:1 sair encerra.</li>
 * <li>Gravação nunca é ligada (o token de entrada desliga gravação/transmissão).</li>
 * </ul>
 */
@Service
public class CallService implements CallRoomAuthorization {

	private static final Logger LOG = LoggerFactory.getLogger(CallService.class);

	private final CallRepository calls;
	private final CallAudience audience;
	private final TopicLookup topics;
	private final EventLookup eventLookup;
	private final MeetingTokenIssuer tokens;
	private final NotificationService notifications;
	private final UserProfileRepository profiles;
	private final UserRateLimiter rateLimiter;
	private final AgeGate ageGate;
	private final ApplicationEventPublisher events;
	private final Clock clock;
	/**
	 * Transação só da entrada. O {@link #join} precisa que a gravação de presença esteja confirmada
	 * antes de pedir o token: o gateway responde à pergunta de autorização gravando a mesma linha.
	 */
	private final TransactionTemplate entrance;

	public CallService(CallRepository calls, CallAudience audience, TopicLookup topics, EventLookup eventLookup,
			MeetingTokenIssuer tokens, NotificationService notifications, UserProfileRepository profiles,
			UserRateLimiter rateLimiter, AgeGate ageGate, ApplicationEventPublisher events, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.entrance = new TransactionTemplate(transactionManager);
		this.calls = calls;
		this.audience = audience;
		this.topics = topics;
		this.eventLookup = eventLookup;
		this.tokens = tokens;
		this.notifications = notifications;
		this.profiles = profiles;
		this.rateLimiter = rateLimiter;
		this.ageGate = ageGate;
		this.events = events;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ começar

	@Transactional
	public CallView startDirect(String conversationId, String actorId, CallKind kind) {
		requireConfigured();
		ageGate.requireCanWrite(actorId);
		if (!audience.isParticipant(conversationId, actorId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Voce nao participa desta conversa.");
		}
		List<String> participants = audience.participantsOf(conversationId);
		if (participants.size() != 2) {
			throw new ApiException(409, "NOT_A_DIRECT_CONVERSATION",
				"Esta conversa tem mais de duas pessoas: use a chamada do grupo.");
		}
		rateLimiter.require(actorId, UserRateLimiter.START_CALL);
		Instant now = clock.instant();
		// A chamada existente do vínculo é devolvida em vez de criar outra: repetir o pedido (dois
		// cliques, retry de rede) leva para a mesma chamada.
		Optional<CallSession> open = calls.findOpenByConversation(conversationId);
		if (open.isPresent()) {
			return existing(open.get(), actorId);
		}
		List<String> audienceIds = participants.stream().filter(id -> !id.equals(actorId)).toList();
		CallSession call = CallSession.start(newId("call"), kind, CallModality.DIRECT, conversationId, null,
			actorId, newRoomId(), displayName(actorId), now);
		calls.create(call, participants, now);
		notifyIncoming(call, actorId, audienceIds);
		publish(call, participants);
		return view(call, actorId, null);
	}

	/** Sala geral do grupo. */
	@Transactional
	public CallView startGroup(String groupId, String actorId, CallKind kind) {
		return startGroup(groupId, null, actorId, kind);
	}

	/**
	 * Abre (ou devolve) o "Tamo junto" do grupo — da sala geral quando {@code topicId} é nulo, do
	 * tópico quando vem o id. Cada sala é independente: abrir a de um tópico não mexe na geral nem
	 * nas dos outros tópicos.
	 *
	 * <p>Quem abre já entra: a sala nasce com essa pessoa dentro. O token de entrada continua saindo
	 * só pelo {@link #join}, que reconfere o vínculo — aqui a entrada é a mesma regra, aplicada no
	 * servidor, e não uma marcação de "convidado".
	 */
	@Transactional
	public CallView startGroup(String groupId, String topicId, String actorId, CallKind kind) {
		requireConfigured();
		ageGate.requireCanWrite(actorId);
		if (audience.roleIn(groupId, actorId) == null) {
			throw new ApiException(403, "NOT_GROUP_MEMBER", "Voce nao e membro deste grupo.");
		}
		if (topicId != null) {
			requireOpenTopic(groupId, topicId);
		}
		rateLimiter.require(actorId, UserRateLimiter.START_CALL);
		Instant now = clock.instant();
		List<String> members = audience.groupMembers(groupId);
		Optional<CallSession> open = topicId != null ? calls.findOpenByTopic(groupId, topicId)
			: calls.findOpenByGroup(groupId);
		if (open.isPresent()) {
			// Quem abre a sala que já existe entra nela; só olhar o estado não põe ninguém dentro.
			enter(open.get(), actorId);
			return view(open.get(), actorId, null);
		}
		CallSession call = CallSession.start(newId("call"), kind, CallModality.GROUP, null, groupId, topicId,
			actorId, newRoomId(), displayName(actorId), now);
		calls.create(call, members, now);
		enter(call, actorId);
		// Nada de toque em grupo: só o aviso "o grupo está ao vivo", uma vez por chamada.
		notifyGroupLive(call, actorId, members);
		publish(call, members);
		return view(call, actorId, null);
	}

	/**
	 * Abre (ou devolve) o "Tamo junto" do encontro. Mesma regra do grupo — nasce "ao vivo" com quem
	 * abriu já dentro, sem toque — mas a sala é do evento, e não de um grupo: quem tem sessão e chega
	 * com o id do evento pode entrar. É o desejo da pessoa que criou o encontro e de quem vai aparecer:
	 * abrir a sala é chamar quem estava pensando em ir.
	 *
	 * <p>O aviso "está ao vivo" vai para quem criou o evento e para quem confirmou presença — a
	 * entrada continua livre.
	 */
	@Transactional
	public CallView startEvent(String eventId, String actorId, CallKind kind) {
		requireConfigured();
		ageGate.requireCanWrite(actorId);
		// A sala e escolha do evento: sem a caixinha ligada, nao ha "Tamo junto" para abrir.
		eventLookup.requireTamoJunto(eventId);
		rateLimiter.require(actorId, UserRateLimiter.START_CALL);
		Instant now = clock.instant();
		List<String> interested = eventLookup.interestedIn(eventId);
		Optional<CallSession> open = calls.findOpenByEvent(eventId);
		if (open.isPresent()) {
			enter(open.get(), actorId);
			return view(open.get(), actorId, null);
		}
		CallSession call = CallSession.forEvent(newId("call"), kind, eventId, actorId, newRoomId(),
			displayName(actorId), now);
		calls.create(call, interested, now);
		enter(call, actorId);
		notifyEventLive(call, actorId, interested);
		publish(call, interested);
		return view(call, actorId, null);
	}

	// ------------------------------------------------------------------ responder (1:1)

	@Transactional
	public CallView answer(String callId, String actorId) {
		ageGate.requireCanWrite(actorId);
		CallSession call = requireCall(callId, actorId);
		if (call.modality() != CallModality.DIRECT) {
			throw new ApiException(409, "NOT_A_DIRECT_CALL", "Chamadas de grupo sao atendidas entrando na sala.");
		}
		if (call.state() == CallState.DECLINED) {
			throw new ApiException(409, "CALL_DECLINED", "A chamada ja foi recusada.");
		}
		if (call.state() == CallState.MISSED) {
			throw new ApiException(409, "CALL_MISSED", "A chamada nao foi atendida a tempo.");
		}
		if (call.state() == CallState.CANCELLED) {
			throw new ApiException(409, "CALL_CANCELLED", "Quem ligou desistiu da chamada.");
		}
		Instant now = clock.instant();
		CallState from = call.state();
		call.moveTo(CallState.CONNECTING, now, null);
		// Atendida, a chamada ganha a janela de abandono: a tolerância de sala vazia é só do grupo.
		call.extendTo(now, CallSession.ABANDON_TIMEOUT);
		if (!calls.persist(call, from)) {
			// Outra aba/ação decidiu primeiro: relê e responde o estado real, sem fingir sucesso.
			CallSession current = requireCall(callId, actorId);
			if (current.state().allowsJoin()) {
				return view(current, actorId, null);
			}
			throw new ApiException(409, "CALL_STATE_CONFLICT", "A chamada mudou de estado.");
		}
		calls.decide(callId, actorId, CallParticipantState.JOINED, now);
		calls.join(callId, actorId, now);
		publish(call, audienceOf(call));
		return view(call, actorId, null);
	}

	@Transactional
	public CallView decline(String callId, String actorId) {
		CallSession call = requireCall(callId, actorId);
		if (call.initiatorId().equals(actorId)) {
			return cancel(callId, actorId);
		}
		if (call.state().isTerminal()) {
			// Recusar duas vezes (aba repetindo o evento) devolve o mesmo resultado.
			return view(call, actorId, null);
		}
		if (!call.state().allowsJoin() && call.state() != CallState.RINGING) {
			throw new ApiException(409, "CALL_STATE_CONFLICT", "A chamada nao esta tocando.");
		}
		Instant now = clock.instant();
		CallState from = call.state();
		call.closeBy(actorId, CallState.DECLINED, now, "DECLINED");
		if (!calls.persist(call, from)) {
			return view(requireCall(callId, actorId), actorId, null);
		}
		calls.decide(callId, actorId, CallParticipantState.DECLINED, now);
		calls.settleInvites(callId, CallParticipantState.MISSED, now);
		notifyEnded(call, actorId);
		publish(call, audienceOf(call));
		return view(call, actorId, null);
	}

	@Transactional
	public CallView cancel(String callId, String actorId) {
		CallSession call = requireCall(callId, actorId);
		if (call.state().isTerminal()) {
			// Quem cancelou repetiu o pedido: o resultado é o mesmo, sem erro.
			return view(call, actorId, null);
		}
		if (!call.initiatorId().equals(actorId)) {
			throw new ApiException(403, "NOT_CALL_INITIATOR", "So quem ligou pode cancelar a chamada.");
		}
		Instant now = clock.instant();
		CallState from = call.state();
		call.closeBy(actorId, CallState.CANCELLED, now, "CANCELLED");
		if (!calls.persist(call, from)) {
			return view(requireCall(callId, actorId), actorId, null);
		}
		calls.settleInvites(callId, CallParticipantState.MISSED, now);
		publish(call, audienceOf(call));
		return view(call, actorId, null);
	}

	// ------------------------------------------------------------------ sala

	/**
	 * Autoriza a entrada e emite um token curto para ESTA sala. O token nunca é armazenado no
	 * cliente nem no servidor: ele vale para esta entrada e expira sozinho.
	 *
	 * <p>Entrar (gravar a presença) e pedir o token são dois passos separados de propósito. O
	 * gateway só assina o token depois de perguntar a este backend, em
	 * {@code /calls/{id}/gateway/entry/{user}}, se a pessoa pode entrar — e essa pergunta grava a
	 * mesma linha que a entrada acabou de gravar. Pedir o token dentro da transação da entrada
	 * deixava este backend esperando o gateway, que esperava este backend: a chamada morria no
	 * prazo do pedido (5 s) e a tela mostrava "chamadas indisponíveis". Por isso a entrada é
	 * confirmada antes e o token sai fora dela, sem lock aberto.
	 */
	public JoinView join(String callId, String actorId) {
		requireConfigured();
		CallSession call = enterStandalone(callId, actorId);
		String token = tokens.tokenFor(call, roomParticipant(call, actorId),
			displayName(actorId), moderatorOf(call, actorId));
		return new JoinView(call.id(), call.kind(), call.modality(), tokens.host(), tokens.tokenTtlSeconds(),
			call.roomId(), token, view(call, actorId, null));
	}

	/**
	 * Entrada numa transação própria, que termina antes do pedido de token. Mesmas regras do
	 * {@link #enter}, só que a transação é do passo, e não do método inteiro.
	 */
	private CallSession enterStandalone(String callId, String actorId) {
		return entrance.execute(status -> {
			CallSession call = requireCall(callId, actorId);
			enter(call, actorId);
			return call;
		});
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CallSession> callOfRoom(String room) {
		return room == null || room.isBlank() ? Optional.empty() : calls.findByRoomId(room);
	}

	/**
	 * Entrada pedida pelo próprio servidor de reunião (gateway de identidade), que só assina o
	 * token depois desta resposta. As mesmas regras do {@link #join}: vínculo, estado e idempotência.
	 */
	@Override
	@Transactional
	public CallEntry entry(String callId, String userId) {
		requireConfigured();
		CallSession call = calls.findById(callId)
			.orElseThrow(() -> new ApiException(404, "CALL_NOT_FOUND", "Chamada nao encontrada ou expirada."));
		enter(call, userId);
		return new CallEntry(call.id(), call.roomId(), userId, call.state().name(), displayName(userId),
			moderatorOf(call, userId));
	}

	/** Vínculo + estado + registro da entrada. Vale tanto para o painel quanto para o gateway. */
	private void enter(CallSession call, String actorId) {
		try {
			// ECA Digital: entrar em chamada (1:1, grupo ou sala aberta) é escrita. Menor sem a
			// autorização do responsável não entra; a recusa é registrada como qualquer outra.
			ageGate.requireCanWrite(actorId);
			audience.requireCanJoin(call, actorId);
		}
		catch (ApiException denied) {
			calls.logEntry(call.id(), actorId, false, denied.code());
			throw denied;
		}
		if (!call.state().allowsJoin()) {
			calls.logEntry(call.id(), actorId, false, call.state().name());
			throw new ApiException(409, terminalCode(call.state()), terminalMessage(call.state()));
		}
		Instant now = clock.instant();
		CallState from = call.state();
		// Entrar duas vezes (recarregou a página, retry da rede) é a mesma entrada, não um segundo
		// participante: join é condicional e o que importa é que a pessoa esteja dentro.
		if (call.eventId() != null) {
			// Sala do encontro não tem convidados: a presença nasce na entrada.
			calls.joinOpenRoom(call.id(), actorId, now);
		}
		else {
			calls.join(call.id(), actorId, now);
		}
		call.extendTo(now, CallSession.ABANDON_TIMEOUT);
		if (from == CallState.CONNECTING) {
			call.moveTo(CallState.ACTIVE, now, null);
		}
		calls.persist(call, from);
		calls.logEntry(call.id(), actorId, true, call.state().name());
		publish(call, audienceOf(call));
	}

	private CallParticipant roomParticipant(CallSession call, String actorId) {
		Instant now = clock.instant();
		return new CallParticipant(call.id() + ":" + actorId, call.id(), actorId, CallParticipantState.JOINED,
			now, now, null, now);
	}

	/** Quem iniciou a chamada é moderador; quem entra depois nunca vira moderador por conta própria. */
	private static boolean moderatorOf(CallSession call, String actorId) {
		return call.initiatorId().equals(actorId);
	}

	@Transactional
	public CallView leave(String callId, String actorId) {
		CallSession call = requireCall(callId, actorId);
		Instant now = clock.instant();
		if (call.state().isTerminal()) {
			calls.leave(callId, actorId, now);
			return view(call, actorId, null);
		}
		if (call.modality() == CallModality.DIRECT) {
			endOpenCall(call, actorId, "LEFT");
			return view(call, actorId, null);
		}
		calls.leave(callId, actorId, now);
		if (calls.countPresentExcluding(callId, null) == 0) {
			// Sala vazia não fecha na hora: o prazo vira a tolerância e quem caiu ou recarregou volta.
			holdEmpty(call, now);
		}
		publish(call, audienceOf(call));
		return view(call, actorId, null);
	}

	@Transactional
	public CallView end(String callId, String actorId) {
		CallSession call = requireCall(callId, actorId);
		if (call.state().isTerminal()) {
			// Encerrar de novo (outra aba, retry) devolve o mesmo resultado sem erro na tela.
			calls.leave(callId, actorId, clock.instant());
			return view(call, actorId, null);
		}
		endOpenCall(call, actorId, "ENDED");
		return view(call, actorId, null);
	}

	/**
	 * Falha de conexão declarada pelo cliente (a tela perdeu a mídia): a pessoa sai da sala em vez
	 * de ficar pendurada como se estivesse dentro. No 1:1 — ou num grupo que ficou sem ninguém —
	 * a chamada termina com falha explícita; num grupo ainda com gente, só quem caiu sai.
	 */
	@Transactional
	public CallView fail(String callId, String actorId) {
		CallSession call = requireCall(callId, actorId);
		Instant now = clock.instant();
		if (call.state().isTerminal()) {
			calls.leave(callId, actorId, now);
			return view(call, actorId, null);
		}
		if (call.modality() == CallModality.GROUP) {
			calls.leave(callId, actorId, now);
			if (calls.countPresentExcluding(callId, null) > 0) {
				publish(call, audienceOf(call));
				return view(call, actorId, null);
			}
			// Mesma tolerância da saída normal: uma queda de rede não apaga a sala do tópico.
			holdEmpty(call, now);
			publish(call, audienceOf(call));
			return view(call, actorId, null);
		}
		CallState from = call.state();
		call.closeBy(actorId, CallState.FAILED, now, "CONNECTION_LOST");
		if (!calls.persist(call, from)) {
			return view(requireCall(callId, actorId), actorId, null);
		}
		calls.settleInvites(callId, CallParticipantState.MISSED, now);
		publish(call, audienceOf(call));
		return view(call, actorId, null);
	}

	// ------------------------------------------------------------------ leitura

	@Transactional(readOnly = true)
	public CallView get(String callId, String actorId) {
		return view(requireCall(callId, actorId), actorId, null);
	}

	/**
	 * O que esta pessoa tem aberto agora. É o que permite voltar de outra página para a chamada em
	 * andamento e mostrar "Tamo junto" ao vivo na conversa e no grupo.
	 */
	@Transactional(readOnly = true)
	public List<CallView> openFor(String actorId) {
		List<CallView> open = new ArrayList<>();
		for (CallSession call : calls.findOpenByParticipant(actorId)) {
			if (isAudience(call, actorId)) {
				open.add(view(call, actorId, null));
			}
		}
		return open;
	}

	/** Chamada aberta da sala geral do vínculo, se quem pergunta pode vê-la. */
	@Transactional(readOnly = true)
	public Optional<CallView> openOf(String conversationId, String groupId, String actorId) {
		return openOf(conversationId, groupId, null, actorId);
	}

	/**
	 * Chamada aberta de um vínculo — da conversa, da sala geral do grupo, ou da sala de um tópico
	 * quando {@code topicId} vem preenchido.
	 */
	@Transactional(readOnly = true)
	public Optional<CallView> openOf(String conversationId, String groupId, String topicId, String actorId) {
		Optional<CallSession> open;
		if (conversationId != null) {
			open = calls.findOpenByConversation(conversationId);
		}
		else if (groupId != null && topicId != null) {
			open = audience.roleIn(groupId, actorId) == null ? Optional.empty()
				: calls.findOpenByTopic(groupId, topicId);
		}
		else if (groupId != null) {
			open = calls.findOpenByGroup(groupId);
		}
		else {
			open = Optional.empty();
		}
		return open.filter(call -> isAudience(call, actorId)).map(call -> view(call, actorId, null));
	}

	/** Sala "Tamo junto" aberta do encontro, se houver. O evento é conferido antes de olhar a sala. */
	@Transactional(readOnly = true)
	public Optional<CallView> openOfEvent(String eventId, String actorId) {
		eventLookup.requireExists(eventId);
		return calls.findOpenByEvent(eventId)
			.filter(call -> isAudience(call, actorId))
			.map(call -> view(call, actorId, null));
	}

	// ------------------------------------------------------------------ worker

	/** Fecha o que venceu (1:1 sem resposta, grupo vazio, sessão abandonada). Idempotente. */
	@Transactional
	public int expire(List<CallSession> expired, Instant now) {
		int closed = 0;
		for (CallSession call : expired) {
			CallState from = call.state();
			// Grupo com gente dentro não fecha por prazo: o abandono é quando a MÍDIA parou, não
			// quando a sala nasceu. Sem isto, uma sala viva seria derrubada uma hora depois de abrir.
			if (from == CallState.ACTIVE && call.modality() == CallModality.GROUP) {
				if (calls.countPresentExcluding(call.id(), null) > 0) {
					continue;
				}
				if (!call.expire(now, "NO_CONNECTION")) {
					continue;
				}
			}
			else if (!call.expire(now, from == CallState.CONNECTING ? "NO_CONNECTION" : "TIMEOUT")) {
				continue;
			}
			if (from == CallState.ACTIVE) {
				calls.abandon(call, from, now, call.endReason());
			}
			else if (!calls.persist(call, from)) {
				continue;
			}
			if (call.modality() == CallModality.DIRECT) {
				closeInvites(call, now);
				notifyMissed(call);
			}
			publish(call, audienceOf(call));
			closed++;
		}
		return closed;
	}

	// ------------------------------------------------------------------ interno

	/**
	 * Sala de grupo que ficou sem ninguém: mantém aberta pela tolerância, com o prazo contado a
	 * partir de agora. A condição é o estado lido: se outra pessoa entrou no meio, a transição não
	 * grava e a sala segue como está.
	 */
	private void holdEmpty(CallSession call, Instant now) {
		call.drained(now);
		calls.extend(call, call.state());
	}

	private void requireOpenTopic(String groupId, String topicId) {
		GroupTopic topic = topics.requireIn(groupId, topicId);
		if (topic.status() != GroupTopicStatus.OPEN) {
			throw new ApiException(409, "TOPIC_ARCHIVED", "Este topico esta arquivado.");
		}
	}

	private void endOpenCall(CallSession call, String actorId, String reason) {
		Instant now = clock.instant();
		CallState from = call.state();
		call.closeBy(actorId, from == CallState.CONNECTING ? CallState.FAILED : CallState.ENDED, now, reason);
		if (!calls.persist(call, from)) {
			return;
		}
		calls.settleInvites(call.id(), CallParticipantState.MISSED, now);
		notifyEnded(call, actorId);
		publish(call, audienceOf(call));
	}

	private void closeInvites(CallSession call, Instant now) {
		calls.settleInvites(call.id(), CallParticipantState.MISSED, now);
	}

	private CallView existing(CallSession call, String actorId) {
		if (!isAudience(call, actorId)) {
			throw new ApiException(403, "NOT_PARTICIPANT", "Chamada sem vinculo com voce.");
		}
		return view(call, actorId, null);
	}

	private boolean isAudience(CallSession call, String actorId) {
		if (call.conversationId() != null) {
			return audience.isParticipant(call.conversationId(), actorId);
		}
		if (call.groupId() != null) {
			return audience.roleIn(call.groupId(), actorId) != null;
		}
		return call.eventId() != null;
	}

	private CallSession requireCall(String callId, String actorId) {
		CallSession call = calls.findById(callId)
			.orElseThrow(() -> new ApiException(404, "CALL_NOT_FOUND", "Chamada nao encontrada ou expirada."));
		audience.requireMember(call, actorId);
		return call;
	}

	private void requireConfigured() {
		if (!tokens.isConfigured()) {
			throw new ApiException(503, "CALLS_NOT_CONFIGURED",
				"As chamadas ainda nao estao configuradas neste ambiente.");
		}
	}

	private String displayName(String userId) {
		return profiles.findById(userId).map(profile -> profile.displayName()).orElse("Alguem");
	}

	private List<String> audienceOf(CallSession call) {
		return call.conversationId() != null ? audience.participantsOf(call.conversationId())
			: call.groupId() != null ? audience.groupMembers(call.groupId())
			: call.eventId() != null ? eventLookup.interestedIn(call.eventId()) : List.of();
	}

	private void publish(CallSession call, List<String> userIds) {
		events.publishEvent(new CallChanged(call.id(), call.conversationId(), call.groupId(), call.eventId(),
			call.state().name(), userIds));
	}

	private void notifyIncoming(CallSession call, String actorId, List<String> recipients) {
		String title = switch (call.kind()) {
			case AUDIO -> "Chamada de audio";
			case VIDEO -> "Chamada de video";
		};
		String body = call.displayName() + " esta ligando para voce.";
		for (String userId : recipients) {
			notifications.create(userId, "CALL_INCOMING", actorId, title, body, callHref(call.id()), "Atender");
		}
	}

	private void notifyGroupLive(CallSession call, String actorId, List<String> members) {
		String body = call.topicId() == null ? "🔴 O grupo esta ao vivo! Entre na chamada quando quiser."
			: "🔴 Tamo junto do topico " + topics.requireIn(call.groupId(), call.topicId()).name()
				+ " ao vivo! Entre na chamada quando quiser.";
		for (String userId : members) {
			if (userId.equals(actorId)) {
				continue;
			}
			// Uma notificação por chamada (a chave é a própria chamada): não repete a cada entrada.
			notifications.createCounted(userId, "CALL_GROUP_LIVE", "call_live:" + call.id(), actorId,
				"Tamo junto ao vivo", body, count -> body, callHref(call.id()), "Entrar");
		}
	}

	private void notifyEventLive(CallSession call, String actorId, List<String> interested) {
		String body = "🔴 O encontro esta ao vivo! Entre na chamada quando quiser.";
		for (String userId : interested) {
			if (userId.equals(actorId)) {
				continue;
			}
			notifications.createCounted(userId, "CALL_EVENT_LIVE", "call_live:" + call.id(), actorId,
				"Tamo junto ao vivo", body, count -> body, callHref(call.id()), "Entrar");
		}
	}

	private void notifyMissed(CallSession call) {
		if (call.state() != CallState.MISSED) {
			return;
		}
		for (String userId : audienceOf(call)) {
			CallParticipantState state = calls.participantState(call.id(), userId);
			if (state == CallParticipantState.MISSED) {
				notifications.create(userId, "CALL_MISSED", call.initiatorId(), "Chamada perdida",
					"Voce perdeu uma chamada de " + call.displayName() + ".", callHref(call.id()), "Ligar de volta");
			}
		}
	}

	private void notifyEnded(CallSession call, String actorId) {
		if (call.state() != CallState.ENDED && call.state() != CallState.DECLINED) {
			return;
		}
		// Quem foi para esta chamada, lido das linhas da própria chamada. Perguntar de novo "quem é
		// do grupo" alcançaria gente que entrou depois de a chamada começar — e avisar quem nunca
		// foi chamado é justamente o vazamento que este caminho não pode ter.
		List<String> recipients = calls.recipientsOf(call.id());
		for (String userId : recipients) {
			if (userId.equals(actorId)) {
				continue;
			}
			notifications.create(userId, "CALL_ENDED", actorId, "Chamada encerrada",
				"A chamada de " + call.displayName() + " foi encerrada.",
				call.conversationId() != null ? "/api/conversations/" + call.conversationId() : callHref(call.id()),
				"Ver conversa");
		}
	}

	static String callHref(String callId) {
		return "/call/" + callId;
	}

	private CallView view(CallSession call, String actorId, Integer participantCount) {
		int present = participantCount != null ? participantCount : calls.countPresentExcluding(call.id(), null);
		return new CallView(call.id(), call.kind(), call.modality(), call.conversationId(), call.groupId(),
			call.topicId(), call.eventId(), call.initiatorId(), call.displayName(), call.state(), call.createdAt(),
			call.answeredAt(), call.endedAt(), call.endReason(), call.expiresAt(), present,
			call.initiatorId().equals(actorId), calls.participantState(call.id(), actorId));
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	/** Sala aleatória: adivinhar o @ de alguém não dá acesso a nada. */
	private static String newRoomId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static String terminalCode(CallState state) {
		return switch (state) {
			case DECLINED -> "CALL_DECLINED";
			case MISSED -> "CALL_MISSED";
			case CANCELLED -> "CALL_CANCELLED";
			case ENDED -> "CALL_ENDED";
			case FAILED -> "CALL_FAILED";
			default -> "CALL_STATE_CONFLICT";
		};
	}

	private static String terminalMessage(CallState state) {
		return switch (state) {
			case DECLINED -> "A chamada foi recusada.";
			case MISSED -> "A chamada nao foi atendida a tempo.";
			case CANCELLED -> "A chamada foi cancelada.";
			case ENDED -> "A chamada ja terminou.";
			case FAILED -> "A chamada falhou.";
			default -> "A chamada nao aceita novas entradas agora.";
		};
	}

	/** Visão da chamada para uma pessoa. Nunca carrega token nem nome de sala. */
	public record CallView(String id, CallKind kind, CallModality modality, String conversationId, String groupId,
			String topicId, String eventId, String initiatorId, String displayName, CallState state, Instant createdAt,
			Instant answeredAt, Instant endedAt, String endReason, Instant expiresAt, int participantCount,
			boolean isInitiator, CallParticipantState myState) {
	}

	/**
	 * Credencial de entrada na sala: {@code host} é o endereço do servidor de reunião, {@code room}
	 * o id aleatório da chamada e {@code jwt} o token assinado. O segredo de assinatura fica só no
	 * servidor.
	 */
	public record JoinView(String callId, CallKind kind, CallModality modality, String host, long tokenTtlSeconds,
			String room, String jwt, CallView call) {
	}

	static Duration ringTimeout() {
		return CallSession.RING_TIMEOUT;
	}
}

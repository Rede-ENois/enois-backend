package com.socialcoisa.rede.messaging.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.network.application.port.out.ConnectionRepository;
import com.socialcoisa.rede.profile.application.port.out.AccountIdentity;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.age.AgePolicyReader;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.sanctions.SanctionGateReader;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Quem pode falar com quem numa conversa — a ÚNICA fonte de verdade para resolver destinatário.
 *
 * <p>Toda rota que recebe o id de outra pessoa (DM, DM corporativa, grupo, canal, mensagem) e todo
 * fluxo de servidor que abre conversa (candidatura, contratação, proposta de freela, pedido de
 * serviço) passa por aqui. Antes, cada controller repetia um pedaço da regra — o {@code /direct}
 * checava bloqueio mas não a existência da conta, o {@code /business-direct} repetia a checagem com
 * outro resultado, e a criação de grupo só olhava o que o criador tinha escondido. O resultado era
 * conversa nasce com participante que não existe (E-04) e regra diferente por tipo de conversa (E-05).</p>
 *
 * <p><b>A ordem das verificações é parte do contrato</b> e é a mesma para todo tipo:</p>
 * <ol>
 * <li><b>Formato do identificador</b> — antes de qualquer consulta. Um id malformado é erro de
 *   entrada (400 {@code INVALID_RECIPIENT}) e nunca vira busca no banco.</li>
 * <li><b>Existência da conta</b> — uma leitura em lote para o conjunto inteiro; quem não existe
 *   recebe 404 {@code USER_NOT_FOUND}. É a resposta que o cliente espera do id que ele mandou.</li>
 * <li><b>Estado da conta</b> — conta excluída/anonimizada responde como inexistente (o {@code @}
 *   removido não identifica mais ninguém); conta banida é recusada com 403 {@code USER_UNAVAILABLE}.
 *   Conta banida no meio de um grupo derruba a criação inteira: o grupo não pode esconder o motivo.</li>
 * <li><b>Você mesmo</b> — não existe conversa direta consigo.</li>
 * <li><b>Bloqueio nos dois sentidos</b> — uma leitura do conjunto de bloqueios cobre todos os
 *   destinatários; conversa de grupo (muitos que não se escolheram) não é silenciada por bloqueio
 *   entre membros, como antes.</li>
 * <li><b>Idade (ECA Digital)</b> — menor só é alcançável por quem tem conexão aceita. JOB e SERVICE
 *   continuam isentos: quem aplica ou contrata inicia o contato pela própria ação.</li>
 * <li><b>Elegibilidade do tipo</b> — o que é específico de um KIND mora em {@link #requireKindAccepts}
 *   e roda por último, depois de todas as regras de pessoa.</li>
 * </ol>
 *
 * <p>A regra depende do estado ATUAL das pessoas: roda ao abrir a conversa, ao reabrir uma existente e
 * em cada mensagem. Checar só na criação deixava um bloqueio (ou uma conexão removida de um menor)
 * feito depois ser ignorado por uma conversa que já existia.</p>
 */
@Component
public class ConversationReachPolicy {

	/** Tipos aceitos por esta política; qualquer outro (ex.: {@code CHANNEL_PUBLIC}) não é conversa. */
	public static final Set<String> KINDS = Set.of("DIRECT", "GROUP", "JOB", "SERVICE", "FREELANCE",
		"BUSINESS_DIRECT");

	private static final int MAX_ID_LENGTH = 64;
	private static final java.util.regex.Pattern ID_FORMAT = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

	/** Tipos em que o bloqueio entre dois membros não cala a conversa inteira. */
	private static final String GROUP = "GROUP";

	private final AgePolicyReader agePolicy;
	private final ConnectionRepository connections;
	private final BlockedUsersReader blockedUsers;
	private final SanctionGateReader sanctionGate;
	private final UserProfileRepository profiles;

	public ConversationReachPolicy(AgePolicyReader agePolicy, ConnectionRepository connections,
			BlockedUsersReader blockedUsers, SanctionGateReader sanctionGate, UserProfileRepository profiles) {
		this.agePolicy = agePolicy;
		this.connections = connections;
		this.blockedUsers = blockedUsers;
		this.sanctionGate = sanctionGate;
		this.profiles = profiles;
	}

	/**
	 * Resolve um único destinatário para abrir (ou reabrir) uma conversa 1:1 do tipo dado.
	 * Devolve o id já validado, para o chamador nunca gravar um valor que não passou por aqui.
	 */
	public String resolveRecipient(String kind, String actorId, String rawRecipientId) {
		String recipientId = requireFormat(rawRecipientId);
		requireAccounts(List.of(actorId, recipientId));
		requireNotSelf(actorId, List.of(recipientId));
		requireNotBlocked(kind, actorId, List.of(recipientId));
		requireMayContactMinors(kind, actorId, List.of(recipientId));
		requireKindAccepts(kind, actorId, List.of(recipientId));
		return recipientId;
	}

	/**
	 * Conversa criada pelo endpoint genérico (DIRECT ou GROUP). O criador entra sozinho no conjunto:
	 * ele já está autenticado e a checagem dele é a de escrita, não a de destinatário.
	 */
	public List<String> resolveParticipants(String kind, String creatorId, Collection<String> rawParticipantIds) {
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		if (rawParticipantIds != null) {
			for (String raw : rawParticipantIds) {
				if (raw != null && !raw.isBlank()) {
					unique.add(requireFormat(raw));
				}
			}
		}
		unique.add(creatorId);
		List<String> all = List.copyOf(unique);
		List<String> others = all.stream().filter(id -> !id.equals(creatorId)).toList();
		requireAccounts(others);
		requireNotSelf(creatorId, others);
		requireNotBlocked(kind, creatorId, others);
		requireMayContactMinors(kind, creatorId, others);
		requireKindAccepts(kind, creatorId, others);
		return all;
	}

	/** Um par 1:1 aberto (ou reaberto) pelo próprio fluxo: candidatura, contratação, proposta, DM. */
	public void requireMayOpenPair(String kind, String actorId, String otherId) {
		// O id pode ter vindo do banco (fluxo de servidor) ou da requisição (rota /direct): o
		// formato vale para os dois, então um id colado na URL nunca chega ao banco.
		requireAccounts(List.of(requireFormat(otherId)));
		requireNotSelf(actorId, List.of(otherId));
		requireNotBlocked(kind, actorId, List.of(otherId));
		requireMayContactMinors(kind, actorId, List.of(otherId));
		requireKindAccepts(kind, actorId, List.of(otherId));
	}

	/** Toda mensagem, com os participantes como estão agora. */
	public void requireMaySend(String kind, String senderId, Collection<String> recipientIds) {
		requireNotBlocked(kind, senderId, recipientIds);
		requireMayContactMinors(kind, senderId, recipientIds);
		requireKindAccepts(kind, senderId, recipientIds);
	}

	/** Um destinatário colado no corpo da mensagem precisa existir e aceitar conversa do tipo. */
	public String resolveMessageRecipient(String kind, String senderId, String rawRecipientId) {
		String recipientId = requireFormat(rawRecipientId);
		requireAccounts(List.of(recipientId));
		requireNotSelf(senderId, List.of(recipientId));
		requireNotBlocked(kind, senderId, List.of(recipientId));
		requireMayContactMinors(kind, senderId, List.of(recipientId));
		requireKindAccepts(kind, senderId, List.of(recipientId));
		return recipientId;
	}

	// ------------------------------------------------------------------ etapas

	/** 1. Formato: o id de conta do Énóis é opaco, curto e sem separadores de caminho. */
	private static String requireFormat(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			throw new ApiException(400, "INVALID_RECIPIENT", "Informe o identificador da conta.");
		}
		if (value.length() > MAX_ID_LENGTH || !ID_FORMAT.matcher(value).matches()) {
			throw new ApiException(400, "INVALID_RECIPIENT", "Identificador de conta invalido.");
		}
		return value;
	}

	/**
	 * 2 e 3. Existência e estado da conta, em UMA leitura para o conjunto inteiro.
	 *
	 * <p>A projeção de identidade não carrega o perfil completo: ubiquidade aqui é a regra, e a
	 * conversa não pode pagar N consultas por N participantes.</p>
	 */
	private void requireAccounts(Collection<String> ids) {
		List<String> wanted = ids.stream().distinct().toList();
		if (wanted.isEmpty()) {
			return;
		}
		Set<String> found = new LinkedHashSet<>();
		for (AccountIdentity identity : profiles.identitiesOf(wanted)) {
			if (identity.username() != null
					&& identity.username().startsWith(com.socialcoisa.rede.profile.domain.UserProfile.REMOVED_USERNAME_PREFIX)) {
				continue;
			}
			found.add(identity.id());
		}
		if (!found.containsAll(wanted)) {
			throw new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado.");
		}
		Set<String> banned = sanctionGate.bannedAmong(wanted);
		if (!banned.isEmpty()) {
			throw new ApiException(403, "USER_UNAVAILABLE", "Nao e possivel conversar com esta conta agora.");
		}
	}

	/** 4. Conversa consigo mesmo não existe (o id já é válido e existe neste ponto). */
	private static void requireNotSelf(String actorId, Collection<String> recipientIds) {
		if (recipientIds.stream().anyMatch(actorId::equals)) {
			throw new ApiException(400, "INVALID_RECIPIENT", "Nao da para abrir uma conversa consigo mesmo.");
		}
	}

	/**
	 * 5. Bloqueio nos dois sentidos: uma leitura do conjunto de bloqueios do ator cobre as duas
	 * direções, sem duas idas ao banco por destinatário.
	 *
	 * <p>Em grupo, quem criou não pode incluir alguém que o próprio criador escondeu (bloqueio do
	 * criador para a pessoa). O bloqueio entre dois outros membros não cala a conversa: ela é de
	 * todos, não um par.</p>
	 */
	private void requireNotBlocked(String kind, String actorId, Collection<String> recipientIds) {
		if (recipientIds.isEmpty()) {
			return;
		}
		if (GROUP.equalsIgnoreCase(kind)) {
			Set<String> hidden = blockedUsers.hiddenIdsFor(actorId);
			if (recipientIds.stream().anyMatch(hidden::contains)) {
				throw new ApiException(403, "USER_BLOCKED", "Nao e possivel conversar com este usuario.");
			}
			return;
		}
		Set<String> blocked = blockedUsers.blockedIdsFor(actorId);
		if (recipientIds.stream().anyMatch(blocked::contains)) {
			throw new ApiException(403, "USER_BLOCKED", "Nao e possivel conversar com este usuario.");
		}
	}

	/** 6. ECA Digital: um menor só é alcançável por quem tem conexão aceita com ele. */
	private void requireMayContactMinors(String kind, String actorId, Collection<String> recipientIds) {
		// JOB e SERVICE são abertos pela própria ação do menor (aplicar, contratar) e continuam.
		if ("JOB".equalsIgnoreCase(kind) || "SERVICE".equalsIgnoreCase(kind) || recipientIds.isEmpty()) {
			return;
		}
		Set<String> minors = agePolicy.minorIds(recipientIds);
		if (!minors.isEmpty() && !connections.findAcceptedAmong(actorId, minors).containsAll(minors)) {
			throw new ApiException(403, "MINOR_DM_RESTRICTED",
				"Para proteger usuarios menores de idade, so e possivel conversar com eles apos uma conexao aceita.");
		}
	}

	/**
	 * 7. Elegibilidade do tipo de conversa. O que é regra de NEGÓCIO de um KIND fica aqui, e não no
	 * controller — assim a mesma regra vale para o endpoint e para o fluxo de servidor que abre a
	 * conversa. Hoje nenhum tipo acrescenta condição além das regras de pessoa; o ponto de extensão
	 * existe para que a próxima regra (ex.: "só empresa verificada") nasça num lugar só.
	 */
	private static void requireKindAccepts(String kind, String actorId, Collection<String> recipientIds) {
		String normalized = kind == null ? "" : kind.trim().toUpperCase(java.util.Locale.ROOT);
		if (!KINDS.contains(normalized)) {
			throw new ApiException(400, "INVALID_CONVERSATION_KIND", "Tipo de conversa invalido.");
		}
	}
}
package com.socialcoisa.rede.groups.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.groups.application.port.out.GroupRepository;
import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupStatus;
import com.socialcoisa.rede.groups.domain.GroupCapability;
import com.socialcoisa.rede.groups.domain.GroupJoinMode;
import com.socialcoisa.rede.groups.domain.GroupJoinRequest;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupPermissions;
import com.socialcoisa.rede.groups.domain.GroupRole;
import com.socialcoisa.rede.groups.domain.GroupSlug;
import com.socialcoisa.rede.groups.domain.JoinOutcome;
import com.socialcoisa.rede.groups.domain.JoinRequestStatus;
import com.socialcoisa.rede.shared.html.SafeHtml;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O ciclo de vida do grupo: criar, editar, entrar, sair, administrar membros e decidir pedidos.
 *
 * A autorização não é comparada aqui: cada operação pergunta por uma {@link GroupCapability} em
 * {@link GroupPermissions}. Assim a régua mora num lugar só, e a tela não é a autoridade.
 */
@Service
public class GroupService {

	private static final int NAME_MAX = 120;
	private static final int SLUG_MAX = 80;
	private static final int MAX_PAGE = 50;

	private final GroupRepository repository;
	private final AuditLogRepository audit;
	private final GroupNotifier notifier;
	private final Clock clock;

	private final com.socialcoisa.rede.coin.application.CoinService coins;

	public GroupService(GroupRepository repository, AuditLogRepository audit, GroupNotifier notifier, Clock clock,
			com.socialcoisa.rede.coin.application.CoinService coins) {
		this.coins = coins;
		this.repository = repository;
		this.audit = audit;
		this.notifier = notifier;
		this.clock = clock;
	}

	@Transactional
	public Group create(String name, String description, String topic, String avatar, String banner,
			GroupJoinMode joinMode, String ownerId) {
		if (!StringUtils.hasText(name)) {
			throw new ApiException(400, "INVALID_NAME", "Nome do grupo e obrigatorio.");
		}
		String finalName = name.trim();
		if (finalName.length() > NAME_MAX) {
			throw new ApiException(400, "INVALID_NAME", "Nome do grupo deve ter ate 120 caracteres.");
		}
		Instant now = clock.instant();
		Group group = Group.create(newId("group"), uniqueSlug(GroupSlug.from(finalName)), finalName,
			trimToNull(description), trimToNull(topic), trimToNull(avatar), trimToNull(banner),
			joinMode == null ? GroupJoinMode.APPROVAL : joinMode, ownerId, now);
		repository.save(group);
		repository.saveMember(GroupMember.join(group.id(), ownerId, GroupRole.OWNER, now));
		repository.adjustMemberCount(group.id(), 1);
		return group;
	}

	@Transactional
	public Group update(String groupId, String name, String description, String topic, String avatar, String banner,
			String bannerHtml, GroupJoinMode joinMode, String actorId) {
		Group group = requireGroup(groupId);
		require(roleOf(groupId, actorId), GroupCapability.EDIT_GROUP);
		// Mesma régua da capa do perfil e da comunidade: nulo mantém, em branco limpa, com texto
		// sanitiza. Um cabeçalho de espaços não é cabeçalho. Avatar/banner seguem a mesma regra
		// (antes o em branco virava nulo e era impossível REMOVER a imagem pela edição).
		String finalBannerHtml = bannerHtml == null ? null
			: (StringUtils.hasText(bannerHtml) ? SafeHtml.requireProfileHtml(bannerHtml) : "");
		String finalAvatar = avatar == null ? null : avatar.trim();
		String finalBanner = banner == null ? null : banner.trim();
		Group updated = group.update(trimToNull(name), trimToNull(description), trimToNull(topic),
			finalAvatar, finalBanner, finalBannerHtml, joinMode, clock.instant());
		repository.save(updated);
		return updated;
	}

	/** Público: a página do grupo abre pelo slug; ações internas usam o id. */
	@Transactional(readOnly = true)
	public Group resolve(String slugOrId) {
		return repository.findBySlug(slugOrId)
			.or(() -> repository.findById(slugOrId))
			.orElseThrow(() -> new ApiException(404, "GROUP_NOT_FOUND", "Grupo nao encontrado."));
	}

	@Transactional(readOnly = true)
	public List<Group> list(String query, Instant before, int limit) {
		return repository.listVisible(StringUtils.hasText(query) ? query.trim() : null, before,
			Math.min(Math.max(limit, 1), MAX_PAGE));
	}

	/** Papel de alguém no grupo; nulo = visitante (não é membro). */
	@Transactional(readOnly = true)
	public GroupRole roleOf(String groupId, String userId) {
		return repository.findMember(groupId, userId).map(GroupMember::role).orElse(null);
	}

	@Transactional(readOnly = true)
	public List<GroupMember> listMembers(String groupId, String actorId) {
		require(roleOf(groupId, actorId), GroupCapability.VIEW_PRIVATE_CONTENT);
		return repository.listMembers(groupId);
	}

	@Transactional(readOnly = true)
	public long countMembers(String groupId) {
		return repository.countMembers(groupId);
	}

	@Transactional(readOnly = true)
	public boolean hasPendingRequest(String groupId, String userId) {
		return repository.findPendingRequest(groupId, userId).isPresent();
	}

	@Transactional
	public JoinOutcome join(String groupId, String userId) {
		Group group = requireGroup(groupId);
		if (!group.status().acceptsNewMembers()) {
			throw new ApiException(409, "GROUP_NOT_ACCEPTING_MEMBERS",
				"Este grupo nao esta aceitando novos membros.");
		}
		if (repository.findMember(groupId, userId).isPresent()) {
			throw new ApiException(409, "ALREADY_MEMBER", "Voce ja e membro deste grupo.");
		}
		if (group.joinMode() == GroupJoinMode.OPEN) {
			repository.saveMember(GroupMember.join(groupId, userId, GroupRole.MEMBER, clock.instant()));
			repository.adjustMemberCount(groupId, 1);
			notifier.memberJoined(group, userId);
			coins.recordPresence(userId);
			return JoinOutcome.MEMBER;
		}
		// Entrada por aprovação: um pendente por pessoa e grupo (o banco também garante).
		if (repository.findPendingRequest(groupId, userId).isPresent()) {
			throw new ApiException(409, "REQUEST_ALREADY_PENDING", "Seu pedido de entrada ja esta pendente.");
		}
		repository.saveJoinRequest(
			GroupJoinRequest.create(newId("group_request"), groupId, userId, null, clock.instant()));
		notifier.joinRequested(group, userId);
		coins.recordPresence(userId);
		return JoinOutcome.PENDING;
	}

	@Transactional
	public void leave(String groupId, String userId) {
		GroupMember member = repository.findMember(groupId, userId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Voce nao e membro deste grupo."));
		if (member.role() == GroupRole.OWNER) {
			throw new ApiException(409, "OWNERSHIP_TRANSFER_REQUIRED",
				"Transfira a propriedade para outro membro antes de sair.");
		}
		repository.removeMember(groupId, userId);
		repository.adjustMemberCount(groupId, -1);
	}

	@Transactional
	public void removeMember(String groupId, String actorId, String targetUserId) {
		require(roleOf(groupId, actorId), GroupCapability.MANAGE_MEMBERS);
		Group group = requireGroup(groupId);
		GroupMember target = repository.findMember(groupId, targetUserId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Essa pessoa nao e membro do grupo."));
		if (target.role() == GroupRole.OWNER) {
			throw new ApiException(409, "CANNOT_REMOVE_OWNER", "O dono do grupo nao pode ser removido.");
		}
		repository.removeMember(groupId, targetUserId);
		repository.adjustMemberCount(groupId, -1);
		notifier.memberRemoved(group, targetUserId, actorId);
	}

	/**
	 * Muda o estado pela PLATAFORMA (restrito, suspenso ou reativação).
	 *
	 * A autorização de administrador da plataforma é do controller (`AdminGuard`); aqui registra a
	 * decisão com o motivo. RESTRICTED para de entrar gente e mantém o conteúdo; SUSPENDED congela
	 * entrada e conteúdo, some da descoberta e mantém a leitura para quem já é membro. Encerrar é
	 * ação do DONO e tem caminho próprio.
	 */
	@Transactional
	public Group changeStatus(String groupId, GroupStatus status, String reason, String actorId) {
		Group group = requireGroup(groupId);
		if (status == GroupStatus.CLOSED) {
			throw new ApiException(400, "INVALID_STATUS", "Encerrar o grupo e acao do dono.");
		}
		Group updated = group.withStatus(status, clock.instant());
		repository.save(updated);
		audit.record(new UserId(actorId), "GROUP_STATUS_CHANGED", groupId,
			"{\"from\":\"" + group.status() + "\",\"to\":\"" + status + "\",\"reason\":\"" + jsonSafe(reason) + "\"}");
		notifier.statusChanged(updated, status.name(), reason, actorId);
		return updated;
	}

	/**
	 * Encerra o grupo (só o dono). Sai da descoberta e o conteúdo deixa de ser acessível para
	 * leitura — a decisão fica na auditoria.
	 */
	@Transactional
	public Group close(String groupId, String actorId) {
		Group group = requireGroup(groupId);
		require(roleOf(groupId, actorId), GroupCapability.CLOSE_GROUP);
		Group closed = group.withStatus(GroupStatus.CLOSED, clock.instant());
		repository.save(closed);
		audit.record(new UserId(actorId), "GROUP_CLOSED", groupId, "{\"slug\":\"" + group.slug() + "\"}");
		return closed;
	}

	/** Passa a propriedade: o novo dono vira OWNER e o antigo continua como ADMIN. */
	@Transactional
	public void transferOwnership(String groupId, String actorId, String targetUserId) {
		Group group = requireGroup(groupId);
		require(roleOf(groupId, actorId), GroupCapability.TRANSFER_OWNERSHIP);
		GroupMember target = repository.findMember(groupId, targetUserId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Essa pessoa nao e membro do grupo."));
		if (target.userId().equals(group.ownerId())) {
			throw new ApiException(409, "ALREADY_OWNER", "Essa pessoa ja e a dona do grupo.");
		}
		GroupMember current = repository.findMember(groupId, actorId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Voce nao e membro deste grupo."));
		repository.save(group.withOwner(targetUserId, clock.instant()));
		repository.saveMember(target.withRole(GroupRole.OWNER));
		repository.saveMember(current.withRole(GroupRole.ADMIN));
		notifier.ownershipTransferred(group, targetUserId, actorId);
	}

	@Transactional(readOnly = true)
	public List<GroupJoinRequest> listPendingRequests(String groupId, String actorId) {
		require(roleOf(groupId, actorId), GroupCapability.VIEW_JOIN_REQUESTS);
		return repository.listPendingRequests(groupId);
	}

	/**
	 * Aprova ou rejeita um pedido.
	 *
	 * A decisão é atômica (só muda o que ainda está PENDENTE): duas aprovações simultâneas não
	 * viram duas associações — a segunda recebe 409. O membro só é criado se a pessoa ainda não
	 * for membro, então o contador não infla.
	 */
	@Transactional
	public GroupJoinRequest decideRequest(String groupId, String requestId, String actorId, boolean approve) {
		require(roleOf(groupId, actorId), GroupCapability.DECIDE_JOIN_REQUESTS);
		Group group = requireGroup(groupId);
		GroupJoinRequest request = repository.findRequestById(requestId)
			.filter(found -> found.groupId().equals(groupId))
			.orElseThrow(() -> new ApiException(404, "REQUEST_NOT_FOUND", "Solicitacao nao encontrada."));
		if (approve && !group.status().acceptsNewMembers()) {
			throw new ApiException(409, "GROUP_NOT_ACCEPTING_MEMBERS",
				"Este grupo nao esta aceitando novos membros.");
		}
		Instant now = clock.instant();
		JoinRequestStatus decidedStatus = approve ? JoinRequestStatus.APPROVED : JoinRequestStatus.REJECTED;
		int changed = repository.markDecided(requestId, decidedStatus, actorId, now);
		if (changed == 0) {
			throw new ApiException(409, "REQUEST_ALREADY_DECIDED", "Essa solicitacao ja foi decidida.");
		}
		if (approve && repository.findMember(groupId, request.userId()).isEmpty()) {
			repository.saveMember(GroupMember.join(groupId, request.userId(), GroupRole.MEMBER, now));
			repository.adjustMemberCount(groupId, 1);
		}
		notifier.requestDecided(group, request.userId(), actorId, approve);
		return repository.findRequestById(requestId).orElse(request);
	}

	private Group requireGroup(String groupId) {
		return repository.findById(groupId)
			.orElseThrow(() -> new ApiException(404, "GROUP_NOT_FOUND", "Grupo nao encontrado."));
	}

	private void require(GroupRole role, GroupCapability capability) {
		if (!GroupPermissions.can(role, capability)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao tem permissao para isso neste grupo.");
		}
	}

	private String uniqueSlug(String slug) {
		if (!repository.existsBySlug(slug)) {
			return slug;
		}
		// O sufixo precisa caber no teto da coluna: encurta a base antes de numerar.
		String base = slug.length() > SLUG_MAX - 4 ? slug.substring(0, SLUG_MAX - 4).replaceAll("-+$", "") : slug;
		for (int n = 2; n < 1000; n++) {
			String candidate = base + "-" + n;
			if (!repository.existsBySlug(candidate)) {
				return candidate;
			}
		}
		throw new ApiException(409, "SLUG_UNAVAILABLE", "Nao foi possivel gerar um endereco para o grupo.");
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	/** O motivo entra no metadado da auditoria (JSON simples): tira o que quebraria a aspa. */
	private static String jsonSafe(String value) {
		return value == null ? "" : value.replace("\"", "'").replace("\n", " ").trim();
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}

package com.socialcoisa.rede.moderation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.communities.application.CommunityService;
import com.socialcoisa.rede.communities.application.port.out.CommunityRepository;
import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.groups.application.GroupService;
import com.socialcoisa.rede.groups.application.port.out.GroupRepository;
import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupRole;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Gerenciamento das comunidades e dos grupos por quem administra a PLATAFORMA.
 *
 * <p>O dono manda na própria comunidade; o administrador local manda no próprio grupo. Este serviço
 * é a outra camada: {@code users.is_admin} age sobre qualquer comunidade ou grupo, sem votação, sem
 * aprovação de dono e sem consultar o papel local de ninguém. A autorização de "é admin" fica no
 * controller ({@code AdminGuard}); aqui não existe outro critério de papel, e nada daqui é chamado
 * pelo caminho de moderação/votação dos moderadores.</p>
 *
 * <p><b>Integridade:</b> antes de excluir, lista os vínculos. A exclusão existente é reaproveitada
 * ({@link CommunityService#deleteCommunity} e o fechamento do grupo, que é o estado final dele na
 * plataforma), em vez de nascer um segundo jeito de apagar a mesma coisa.</p>
 */
@Service
public class AdminEntityService {

	/** Motivo mínimo: o mesmo piso das demais decisões administrativas. */
	static final int MIN_REASON = 10;
	static final int MAX_REASON = 2000;
	/** Teto da listagem administrativa de membros numa página. */
	static final int MAX_PAGE = 100;

	private final CommunityService communities;
	private final CommunityRepository communityRepository;
	private final GroupService groups;
	private final GroupRepository groupRepository;
	private final UserProfileRepository profiles;
	private final AuditTrail audit;
	private final Clock clock;

	AdminEntityService(CommunityService communities, CommunityRepository communityRepository, GroupService groups,
			GroupRepository groupRepository, UserProfileRepository profiles, AuditTrail audit, Clock clock) {
		this.communities = communities;
		this.communityRepository = communityRepository;
		this.groups = groups;
		this.groupRepository = groupRepository;
		this.profiles = profiles;
		this.audit = audit;
		this.clock = clock;
	}

	// ---------------------------------------------------------------- comunidade

	/**
	 * Exclui a comunidade, seja de quem for. Reaproveita a exclusão do módulo com a sobreposição de
	 * administrador (o mesmo caminho que o dono usa: apaga membros e a comunidade numa transação) e
	 * registra quem decidiu e quantos vínculos saíram.
	 */
	@Transactional
	public Deletion deleteCommunity(String adminId, String communityId, String reason) {
		String cleanReason = requireReason(reason, "exclusão da comunidade");
		Community community = communityRepository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade não encontrada."));
		int members = communityRepository.countMembers(communityId);
		String ownerId = community.createdBy();
		boolean adminWasMember = !adminId.equals(ownerId)
			&& communityRepository.findMember(communityId, adminId).isPresent();

		communities.deleteCommunity(communityId, adminId, true);

		audit.record(adminId, "ADMIN_COMMUNITY_DELETED", communityId,
			"{\"name\":" + quote(community.name()) + ",\"ownerId\":" + quote(ownerId) + ",\"members\":" + members
				+ ",\"reason\":" + quote(cleanReason) + ",\"detachedAssociations\":" + communityAssociations() + "}");
		return new Deletion(communityId, community.name(), "DELETED", members, ownerId, adminWasMember, 0);
	}

	/** Perfil visto pelo painel: o mesmo público + quantos vínculos a exclusão levaria. */
	@Transactional(readOnly = true)
	public CommunityOverview communityOverview(String communityId) {
		Community community = communityRepository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade não encontrada."));
		List<CommunityMember> members = communityRepository.findMembersByCommunity(communityId);
		long admins = members.stream().filter(member -> member.role().name().equals("ADMIN")).count();
		return new CommunityOverview(community.id(), community.name(), community.createdBy(), members.size(),
			(int) admins);
	}

	/**
	 * Lista administrativa de membros: quem está dentro, com papel e desde quando — em PÁGINAS, com
	 * busca por nome ou @ e a contagem total. Dono e admin local continuam vendo a lista deles pelo
	 * caminho normal; esta aqui existe para o admin ver mesmo sem participar.
	 */
	@Transactional(readOnly = true)
	public MemberPage communityMembers(String communityId, String query, int page, int size) {
		Community community = communityRepository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade não encontrada."));
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<CommunityMember> all = communityRepository.findMembersByCommunity(communityId);
		List<MemberRow> rows = new ArrayList<>();
		for (CommunityMember member : all) {
			UserProfile profile = profiles.findById(member.userId()).orElse(null);
			String username = profile == null ? null : profile.username();
			String name = profile == null ? null : profile.displayWithNickname();
			if (!matches(needle, username, name)) {
				continue;
			}
			rows.add(new MemberRow(member.userId(), username, name, profile == null ? null : profile.avatar(),
				member.role().name(), member.joinedAt()));
		}
		rows.sort((a, b) -> b.joinedAt().compareTo(a.joinedAt()));
		return page(community.id(), community.name(), rows, page, size);
	}

	/**
	 * Tira uma pessoa da comunidade, seja de quem for — inclusive a conta do dono. Diferente do
	 * caminho de membro comum, o admin não precisa ser dono nem admin local.
	 */
	@Transactional
	public MemberRemoval removeCommunityMember(String adminId, String communityId, String targetUserId,
			String reason) {
		String cleanReason = requireReason(reason, "remoção do membro");
		Community community = communityRepository.findById(communityId)
			.orElseThrow(() -> new ApiException(404, "COMMUNITY_NOT_FOUND", "Comunidade não encontrada."));
		var member = communityRepository.findMember(communityId, targetUserId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Essa pessoa não é membro da comunidade."));
		communityRepository.removeMember(communityId, targetUserId);
		audit.record(adminId, "ADMIN_COMMUNITY_MEMBER_REMOVED", communityId,
			"{\"memberId\":" + quote(targetUserId) + ",\"role\":\"" + member.role() + "\",\"reason\":"
				+ quote(cleanReason) + "}");
		return new MemberRemoval(community.id(), targetUserId, member.role().name(), "REMOVED");
	}

	// --------------------------------------------------------------------- grupo

	/**
	 * Exclui o grupo pela plataforma, seja de quem for: o conteúdo do grupo (tópicos, publicações,
	 * comentários e reações), as associações (membros e pedidos de ingresso) e a linha do grupo saem
	 * juntos, numa transação. É a exclusão que o pedido do painel chama.
	 *
	 * <p>Para o caso em que a preservação do histórico vale mais do que apagar (conversa de terceiros
	 * que não decidiram nada), {@link #closeGroup} mantém o conteúdo e só desliga o grupo.</p>
	 */
	@Transactional
	public Deletion deleteGroup(String adminId, String groupId, String reason) {
		String cleanReason = requireReason(reason, "exclusão do grupo");
		Group group = groupRepository.findById(groupId)
			.orElseThrow(() -> new ApiException(404, "GROUP_NOT_FOUND", "Grupo não encontrado."));
		String slug = group.slug();
		String ownerId = group.ownerId();
		int members = (int) groupRepository.countMembers(groupId);
		boolean adminWasMember = !adminId.equals(ownerId)
			&& groupRepository.findMember(groupId, adminId).isPresent();

		int removedPosts = groups.purgeByAdmin(groupId);

		audit.record(adminId, "ADMIN_GROUP_DELETED", groupId,
			"{\"slug\":" + quote(slug) + ",\"ownerId\":" + quote(ownerId) + ",\"members\":" + members
				+ ",\"posts\":" + removedPosts + ",\"reason\":" + quote(cleanReason)
				+ ",\"detachedAssociations\":" + groupAssociations() + "}");
		return new Deletion(groupId, group.name(), "DELETED", members, ownerId, adminWasMember, removedPosts);
	}

	/**
	 * Desliga o grupo sem apagar conteúdo: sai da descoberta e não aceita mais ninguém, mas quem já
	 * participava continua lendo o que existia. Versão "leve" da exclusão, para quando o conteúdo é
	 * histórico e não objeto da decisão.
	 */
	@Transactional
	public Deletion closeGroup(String adminId, String groupId, String reason) {
		String cleanReason = requireReason(reason, "encerramento do grupo");
		Group group = groupRepository.findById(groupId)
			.orElseThrow(() -> new ApiException(404, "GROUP_NOT_FOUND", "Grupo não encontrado."));
		int members = (int) groupRepository.countMembers(groupId);
		boolean adminWasMember = !adminId.equals(group.ownerId())
			&& groupRepository.findMember(groupId, adminId).isPresent();
		// A auditoria da transição (ADMIN_GROUP_CLOSED, com o motivo) é do serviço de grupo; aqui só
		// se decide — duas linhas para o mesmo fato poluiriam a trilha.
		groups.closeByAdmin(groupId, adminId, cleanReason);
		return new Deletion(groupId, group.name(), "CLOSED", members, group.ownerId(), adminWasMember, 0);
	}

	@Transactional(readOnly = true)
	public GroupOverview groupOverview(String groupId) {
		Group group = groupRepository.findById(groupId)
			.orElseThrow(() -> new ApiException(404, "GROUP_NOT_FOUND", "Grupo não encontrado."));
		List<GroupMember> members = groupRepository.listMembers(groupId);
		long admins = members.stream().filter(member -> member.role() == GroupRole.ADMIN).count();
		return new GroupOverview(group.id(), group.slug(), group.name(), group.ownerId(), group.status().name(),
			members.size(), (int) admins);
	}

	/** Lista administrativa de membros do grupo, com as mesmas regras da comunidade. */
	@Transactional(readOnly = true)
	public MemberPage groupMembers(String groupId, String query, int page, int size) {
		Group group = groupRepository.findById(groupId)
			.orElseThrow(() -> new ApiException(404, "GROUP_NOT_FOUND", "Grupo não encontrado."));
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<MemberRow> rows = new ArrayList<>();
		for (GroupMember member : groupRepository.listMembers(groupId)) {
			UserProfile profile = profiles.findById(member.userId()).orElse(null);
			String username = profile == null ? null : profile.username();
			String name = profile == null ? null : profile.displayWithNickname();
			if (!matches(needle, username, name)) {
				continue;
			}
			rows.add(new MemberRow(member.userId(), username, name, profile == null ? null : profile.avatar(),
				member.role().name(), member.joinedAt()));
		}
		rows.sort((a, b) -> b.joinedAt().compareTo(a.joinedAt()));
		return page(group.id(), group.name(), rows, page, size);
	}

	/**
	 * Tira uma pessoa do grupo, inclusive o dono: o admin da plataforma não depende da hierarquia
	 * local. Se era o dono, o grupo passa para o membro mais antigo que ficou; sem ninguém, fica sem
	 * dono — estado que só a plataforma pode produzir e que a próxima intervenção resolve.
	 */
	@Transactional
	public MemberRemoval removeGroupMember(String adminId, String groupId, String targetUserId, String reason) {
		String cleanReason = requireReason(reason, "remoção do membro");
		Group group = groupRepository.findById(groupId)
			.orElseThrow(() -> new ApiException(404, "GROUP_NOT_FOUND", "Grupo não encontrado."));
		GroupMember target = groupRepository.findMember(groupId, targetUserId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Essa pessoa não é membro do grupo."));
		String previousRole = target.role().name();
		// A auditoria da remoção (e da troca de dono, quando for o caso) fica no serviço de grupo,
		// junto com a transição: uma linha por fato, com o motivo.
		groups.removeMemberByAdmin(groupId, adminId, targetUserId, cleanReason);
		return new MemberRemoval(group.id(), targetUserId, previousRole, "REMOVED");
	}

	// ------------------------------------------------------------------ interno

	private MemberPage page(String entityId, String name, List<MemberRow> rows, int requestedPage, int requestedSize) {
		int size = Math.max(1, Math.min(requestedSize <= 0 ? 50 : requestedSize, MAX_PAGE));
		int total = rows.size();
		int pages = (int) Math.ceil(total / (double) size);
		int page = Math.max(0, requestedPage);
		int from = Math.min(page * size, total);
		int to = Math.min(from + size, total);
		return new MemberPage(entityId, name, total, page, size, pages, rows.subList(from, to));
	}

	private static boolean matches(String needle, String username, String name) {
		if (needle.isEmpty()) {
			return true;
		}
		return (username != null && username.toLowerCase(Locale.ROOT).contains(needle))
			|| (name != null && name.toLowerCase(Locale.ROOT).contains(needle));
	}

	static String requireReason(String reason, String what) {
		String clean = reason == null ? "" : reason.trim();
		if (clean.length() < MIN_REASON) {
			throw new ApiException(400, "REASON_REQUIRED",
				"Explique o motivo de " + what + " (pelo menos " + MIN_REASON + " caracteres).");
		}
		if (clean.length() > MAX_REASON) {
			throw new ApiException(400, "REASON_TOO_LONG",
				"O motivo pode ter no máximo " + MAX_REASON + " caracteres.");
		}
		return clean;
	}

	/** Vínculos que saem junto com a comunidade — o porquê de existir a exclusão transacional. */
	private static String communityAssociations() {
		return "[\"members\",\"favorites\",\"pinned_posts\",\"channels\",\"channel_posts\"]";
	}

	/** Vínculos que saem junto com o grupo na exclusão física. */
	private static String groupAssociations() {
		return "[\"members\",\"join_requests\",\"topics\",\"posts\",\"comments\",\"reactions\"]";
	}

	private static String quote(String value) {
		if (value == null) {
			return "null";
		}
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	// ------------------------------------------------------------------ modelos

	/** O que a exclusão levou: quem decidiu já vai na auditoria; aqui fica o que a tela mostra. */
	public record Deletion(String id, String name, String outcome, int affectedMembers, String ownerId,
			boolean adminWasMember, int removedPosts) {
	}

	public record CommunityOverview(String id, String name, String ownerId, int members, int admins) {
	}

	public record GroupOverview(String id, String slug, String name, String ownerId, String status, int members,
			int admins) {
	}

	public record MemberRow(String userId, String username, String name, String avatar, String role,
			Instant joinedAt) {
	}

	public record MemberPage(String entityId, String name, int total, int page, int size, int pages,
			List<MemberRow> items) {
	}

	public record MemberRemoval(String entityId, String userId, String role, String outcome) {
	}
}
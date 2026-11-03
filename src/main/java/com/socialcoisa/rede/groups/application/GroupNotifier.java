package com.socialcoisa.rede.groups.application;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.groups.application.port.out.GroupContentRepository;
import com.socialcoisa.rede.groups.application.port.out.GroupRepository;
import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupPost;
import com.socialcoisa.rede.groups.domain.GroupRole;
import com.socialcoisa.rede.notifications.application.NotificationService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.shared.outbox.OutboxEventHandler;

/**
 * Notificações do contexto de Grupos.
 *
 * Regra de privacidade: o corpo NUNCA leva o texto da publicação/comentário. A caixa de
 * notificações é lida fora do grupo e o destino (a página do grupo) é quem exige autorização no
 * backend — então só o nome do grupo, que já é metadado público da descoberta, viaja.
 *
 * Quem administra recebe com kind/cta próprios; o autor de uma ação nunca é notificado dela.
 */
@Component
class GroupNotifier implements OutboxEventHandler {

	/** Outbox event appended when a post is published; its aggregate id is the post id. */
	static final String POST_PUBLISHED = "GroupPostPublished";

	private final NotificationService notifications;
	private final GroupRepository groups;
	private final GroupContentRepository content;
	private final UserProfileRepository profiles;

	GroupNotifier(NotificationService notifications, GroupRepository groups, GroupContentRepository content,
			UserProfileRepository profiles) {
		this.notifications = notifications;
		this.groups = groups;
		this.content = content;
		this.profiles = profiles;
	}

	@Override
	public void handle(String eventType, String aggregateId, String payload) {
		if (POST_PUBLISHED.equals(eventType)) {
			content.findPost(aggregateId).ifPresent(this::newPost);
		}
	}

	/**
	 * Every other member hears about a new post, one notification per group: while it is unread the
	 * next posts update it ("3 novas publicações em X") instead of piling up, and the push goes out
	 * only for the first. A post removed (or a group closed to its members) before the worker ran notifies nobody.
	 *
	 * ponytail: one lookup and one write per member inside the outbox worker transaction; fine for
	 * groups of a few thousand members, batch the upsert per group if groups grow past that.
	 */
	void newPost(GroupPost post) {
		if (!post.isPublished()) {
			return;
		}
		Group group = groups.findById(post.groupId()).orElse(null);
		if (group == null || !group.status().readableByMembers()) {
			return;
		}
		String authorName = nameOf(post.authorId());
		String groupKey = "GROUP_NEW_POST:group:" + group.id();
		for (GroupMember member : groups.listMembers(group.id())) {
			if (member.userId().equals(post.authorId())) {
				continue;
			}
			notifications.createCounted(member.userId(), "GROUP_NEW_POST", groupKey, post.authorId(),
				"Novas publicações em " + group.name(), authorName + " publicou em " + group.name() + ".",
				count -> count + " novas publicações em " + group.name() + ".", href(group), "Abrir grupo");
		}
	}

	/** Entrada aberta: avisa quem administra que a pessoa entrou (o pedido por aprovação já avisa). */
	void memberJoined(Group group, String memberId) {
		notifyManagers(group, memberId, "GROUP_MEMBER_JOINED", "Novo membro",
			" entrou em " + group.name() + ".", "Ver grupo");
	}

	/** Pedido pendente: avisa quem administra. O solicitante ainda não é membro e não recebe nada. */
	void joinRequested(Group group, String requesterId) {
		notifyManagers(group, requesterId, "GROUP_JOIN_REQUEST", "Pedido de entrada",
			" pediu para entrar em " + group.name() + ".", "Analisar pedido");
	}

	void requestDecided(Group group, String requesterId, String actorId, boolean approved) {
		String kind = approved ? "GROUP_JOIN_APPROVED" : "GROUP_JOIN_REJECTED";
		String title = approved ? "Entrada aprovada" : "Entrada recusada";
		String body = approved
			? "Seu pedido para entrar em " + group.name() + " foi aprovado."
			: "Seu pedido para entrar em " + group.name() + " foi recusado.";
		notifications.create(requesterId, kind, actorId, title, body, href(group),
			approved ? "Abrir grupo" : "Ver grupo");
	}

	void memberRemoved(Group group, String targetUserId, String actorId) {
		if (targetUserId.equals(actorId)) {
			return;
		}
		notifications.create(targetUserId, "GROUP_MEMBER_REMOVED", actorId, "Removido do grupo",
			"Você foi removido de " + group.name() + ".", href(group), "Ver grupo");
	}

	void ownershipTransferred(Group group, String newOwnerId, String actorId) {
		if (newOwnerId.equals(actorId)) {
			return;
		}
		notifications.create(newOwnerId, "GROUP_OWNERSHIP_TRANSFERRED", actorId, "Você agora é dono",
			"A propriedade do grupo " + group.name() + " passou para você.", href(group), "Abrir grupo");
	}

	/** Comentário: avisa o autor da publicação e, numa resposta, também o autor do comentário pai. */
	void comment(Group group, GroupPost post, String commentAuthorId, String parentAuthorId) {
		if (!post.authorId().equals(commentAuthorId)) {
			notifications.create(post.authorId(), "NEW_GROUP_COMMENT", commentAuthorId, "Novo comentário",
				nameOf(commentAuthorId) + " comentou na sua publicação em " + group.name() + ".",
				href(group), "Abrir grupo");
		}
		if (parentAuthorId != null && !parentAuthorId.equals(commentAuthorId)
				&& !parentAuthorId.equals(post.authorId())) {
			notifications.create(parentAuthorId, "NEW_GROUP_REPLY", commentAuthorId, "Nova resposta",
				nameOf(commentAuthorId) + " respondeu ao seu comentário em " + group.name() + ".",
				href(group), "Abrir grupo");
		}
	}

	/** Moderação local: quem teve o conteúdo removido por outra pessoa fica sabendo. */
	void contentRemoved(Group group, String authorId, String actorId, String what) {
		if (authorId.equals(actorId)) {
			return;
		}
		notifications.create(authorId, "GROUP_CONTENT_REMOVED", actorId, "Conteúdo removido",
			"Sua " + what + " em " + group.name() + " foi removida pela moderação.", href(group), "Ver grupo");
	}

	/** Mudança de estado pela plataforma: o dono é o responsável e precisa saber. */
	void statusChanged(Group group, String status, String reason, String actorId) {
		String body = "O grupo " + group.name() + " agora está " + status + ".";
		if (StringUtils.hasText(reason)) {
			body += " Motivo: " + reason.trim();
		}
		notifications.create(group.ownerId(), "GROUP_STATUS_CHANGED", actorId, "Estado do grupo alterado",
			body, href(group), "Ver grupo");
	}

	private void notifyManagers(Group group, String actorId, String kind, String title, String suffix, String cta) {
		String actorName = nameOf(actorId);
		for (GroupMember member : groups.listMembers(group.id())) {
			if (member.role() != GroupRole.OWNER && member.role() != GroupRole.ADMIN) {
				continue;
			}
			if (member.userId().equals(actorId)) {
				continue;
			}
			notifications.create(member.userId(), kind, actorId, title, actorName + suffix, href(group), cta);
		}
	}

	private String nameOf(String userId) {
		return profiles.findById(userId).map(profile -> profile.name()).orElse("Alguém");
	}

	private static String href(Group group) {
		return "/grupos/" + group.slug();
	}
}

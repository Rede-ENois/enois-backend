package com.socialcoisa.rede.groups.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.out.AuditLogRepository;
import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.groups.application.port.out.GroupContentRepository;
import com.socialcoisa.rede.groups.application.port.out.GroupRepository;
import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupCapability;
import com.socialcoisa.rede.groups.domain.GroupComment;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupPermissions;
import com.socialcoisa.rede.groups.domain.GroupPost;
import com.socialcoisa.rede.groups.domain.GroupReaction;
import com.socialcoisa.rede.groups.domain.GroupRole;
import com.socialcoisa.rede.groups.domain.GroupTopic;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Tópicos e feed privado do grupo.
 *
 * A autorização é a mesma tabela de capacidades do resto do módulo: ler exige ser membro
 * (`VIEW_PRIVATE_CONTENT`), administrar tópicos exige `MANAGE_TOPICS`, publicar exige `PUBLISH`.
 *
 * A publicação SEMPRE confere que o tópico é daquele grupo — o id existir não basta. A V81 ficou
 * sem chave estrangeira composta (a collation de produção quebrava o boot), então a checagem mora
 * aqui, e é testada.
 */
@Service
public class GroupFeedService {

	private static final int TOPIC_NAME_MAX = 80;
	private static final int TOPIC_DESCRIPTION_MAX = 300;
	private static final int MAX_PAGE = 50;

	private final GroupRepository groups;
	private final GroupContentRepository content;
	private final AuditLogRepository audit;
	private final GroupNotifier notifier;
	private final com.socialcoisa.rede.shared.age.AgeGate ageGate;
	private final com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate;
	private final Clock clock;

	public GroupFeedService(GroupRepository groups, GroupContentRepository content, AuditLogRepository audit,
			GroupNotifier notifier, com.socialcoisa.rede.shared.age.AgeGate ageGate,
			com.socialcoisa.rede.shared.sanctions.SanctionGate sanctionGate, Clock clock) {
		this.groups = groups;
		this.content = content;
		this.audit = audit;
		this.notifier = notifier;
		this.ageGate = ageGate;
		this.sanctionGate = sanctionGate;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<GroupTopic> listTopics(String groupId, String actorId) {
		requireReadable(groupId);
		requireMember(groupId, actorId);
		return content.listTopics(groupId);
	}

	@Transactional
	public GroupTopic createTopic(String groupId, String actorId, String name, String description) {
		require(roleOf(groupId, actorId), GroupCapability.MANAGE_TOPICS);
		if (!StringUtils.hasText(name)) {
			throw new ApiException(400, "INVALID_TOPIC_NAME", "Nome do topico e obrigatorio.");
		}
		String finalName = name.trim();
		if (finalName.length() > TOPIC_NAME_MAX) {
			throw new ApiException(400, "INVALID_TOPIC_NAME", "Nome do topico deve ter ate 80 caracteres.");
		}
		if (StringUtils.hasText(description) && description.trim().length() > TOPIC_DESCRIPTION_MAX) {
			throw new ApiException(400, "INVALID_TOPIC_DESCRIPTION",
				"Descricao do topico deve ter ate 300 caracteres.");
		}
		if (content.topicNameExists(groupId, finalName)) {
			throw new ApiException(409, "TOPIC_EXISTS", "Ja existe um topico com esse nome.");
		}
		GroupTopic topic = GroupTopic.create(newId("group_topic"), groupId, finalName, trimToNull(description),
			content.nextTopicPosition(groupId), clock.instant());
		content.saveTopic(topic);
		return topic;
	}

	@Transactional
	public GroupTopic updateTopic(String groupId, String topicId, String name, String description, String actorId) {
		require(roleOf(groupId, actorId), GroupCapability.MANAGE_TOPICS);
		GroupTopic topic = requireTopic(groupId, topicId);
		String finalName = name == null ? null : name.trim();
		if (finalName != null) {
			if (finalName.isEmpty()) {
				throw new ApiException(400, "INVALID_TOPIC_NAME", "Nome do topico e obrigatorio.");
			}
			if (finalName.length() > TOPIC_NAME_MAX) {
				throw new ApiException(400, "INVALID_TOPIC_NAME", "Nome do topico deve ter ate 80 caracteres.");
			}
			if (!finalName.equalsIgnoreCase(topic.name()) && content.topicNameExists(groupId, finalName)) {
				throw new ApiException(409, "TOPIC_EXISTS", "Ja existe um topico com esse nome.");
			}
		}
		String finalDescription = description == null ? null : description.trim();
		if (finalDescription != null && finalDescription.length() > TOPIC_DESCRIPTION_MAX) {
			throw new ApiException(400, "INVALID_TOPIC_DESCRIPTION",
				"Descricao do topico deve ter ate 300 caracteres.");
		}
		GroupTopic updated = topic.update(finalName, finalDescription, clock.instant());
		content.saveTopic(updated);
		return updated;
	}

	@Transactional
	public GroupTopic archiveTopic(String groupId, String topicId, String actorId) {
		require(roleOf(groupId, actorId), GroupCapability.MANAGE_TOPICS);
		GroupTopic archived = requireTopic(groupId, topicId).archive(clock.instant());
		content.saveTopic(archived);
		return archived;
	}

	/** Reordena a lista inteira: a ordem enviada precisa conter TODOS os tópicos do grupo, uma vez. */
	@Transactional
	public List<GroupTopic> reorderTopics(String groupId, List<String> orderedTopicIds, String actorId) {
		require(roleOf(groupId, actorId), GroupCapability.MANAGE_TOPICS);
		List<GroupTopic> atuais = content.listTopics(groupId);
		Set<String> enviados = orderedTopicIds == null ? Set.of() : new HashSet<>(orderedTopicIds);
		Set<String> existentes = new HashSet<>(atuais.stream().map(GroupTopic::id).toList());
		if (orderedTopicIds == null || enviados.size() != orderedTopicIds.size()
				|| !enviados.equals(existentes)) {
			throw new ApiException(400, "INVALID_ORDER", "A ordem precisa listar todos os topicos do grupo.");
		}
		Instant now = clock.instant();
		for (int index = 0; index < orderedTopicIds.size(); index++) {
			String topicId = orderedTopicIds.get(index);
			GroupTopic topic = atuais.stream().filter(item -> item.id().equals(topicId)).findFirst()
				.orElseThrow(() -> new ApiException(400, "INVALID_ORDER", "Topico desconhecido na ordem."));
			content.saveTopic(topic.withPosition(index, now));
		}
		return content.listTopics(groupId);
	}

	@Transactional
	public GroupPost publish(String groupId, String topicId, String actorId, String text, List<String> mediaIds) {
		// Os mesmos portoes do feed: ECA (menor sem autorizacao nao publica) e sancao de
		// moderacao (conta restrita nao publica). Sem eles, a publicacao de grupo era um buraco
		// por onde a regra do feed era contornada.
		ageGate.requireCanWrite(actorId);
		sanctionGate.requireNotRestricted(actorId);
		Group group = requireGroup(groupId);
		require(roleOf(groupId, actorId), GroupCapability.PUBLISH);
		if (!group.status().acceptsNewContent()) {
			throw new ApiException(409, "GROUP_NOT_ACCEPTING_CONTENT", "Este grupo nao esta aceitando publicacoes.");
		}
		GroupTopic topic = requireTopic(groupId, topicId);
		if (!topic.acceptsPosts()) {
			throw new ApiException(409, "TOPIC_ARCHIVED", "Este topico esta arquivado.");
		}
		if (!StringUtils.hasText(text)) {
			throw new ApiException(400, "EMPTY_CONTENT", "Escreva alguma coisa antes de publicar.");
		}
		String clean = PlainText.defuse(text.trim());
		if (clean.length() > GroupPost.MAX_CONTENT) {
			throw new ApiException(400, "CONTENT_TOO_LONG",
				"Publicacao de grupo deve ter ate 1.200 caracteres.");
		}
		GroupPost post = GroupPost.publish(newId("group_post"), groupId, topicId, actorId, clean, mediaIds,
			clock.instant());
		content.savePost(post);
		return post;
	}

	@Transactional(readOnly = true)
	public List<GroupPost> listPosts(String groupId, String topicId, String actorId, Instant before, int limit) {
		requireReadable(groupId);
		requireMember(groupId, actorId);
		return content.listPosts(groupId, StringUtils.hasText(topicId) ? topicId : null, before,
			Math.min(Math.max(limit, 1), MAX_PAGE));
	}

	/**
	 * Remoção (moderação local ou o próprio autor).
	 *
	 * O autor apaga o que escreveu; quem modera apaga o dos outros (`MODERATE_CONTENT`). A
	 * publicação vira REMOVED — sai do feed e das listagens, e a ação fica na auditoria da
	 * plataforma (o administrador local não apaga prova de denúncia, mas remover o conteúdo do
	 * grupo é o trabalho dele).
	 */
	@Transactional
	public void removePost(String groupId, String postId, String actorId) {
		GroupPost post = requirePost(groupId, postId);
		if (!post.isPublished()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Publicacao nao encontrada neste grupo.");
		}
		if (!post.authorId().equals(actorId)) {
			require(roleOf(groupId, actorId), GroupCapability.MODERATE_CONTENT);
		}
		Group group = requireGroup(groupId);
		content.savePost(post.remove(clock.instant()));
		audit.record(new UserId(actorId), "GROUP_POST_REMOVED", postId, "{\"groupId\":\"" + groupId + "\"}");
		notifier.contentRemoved(group, post.authorId(), actorId, "publicação");
	}

	/** Comentário: o autor apaga o próprio; quem modera apaga o dos outros. */
	@Transactional
	public void removeComment(String groupId, String postId, String commentId, String actorId) {
		GroupPost post = requirePost(groupId, postId);
		GroupComment comment = content.findComment(commentId)
			.filter(item -> item.postId().equals(postId) && item.isPublished())
			.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND",
				"Comentario nao encontrado nesta publicacao."));
		if (!comment.authorId().equals(actorId)) {
			require(roleOf(groupId, actorId), GroupCapability.MODERATE_CONTENT);
		}
		Group group = requireGroup(groupId);
		content.saveComment(comment.remove(clock.instant()));
		refreshCounters(post);
		audit.record(new UserId(actorId), "GROUP_COMMENT_REMOVED", commentId,
			"{\"groupId\":\"" + groupId + "\",\"postId\":\"" + postId + "\"}");
		notifier.contentRemoved(group, comment.authorId(), actorId, "comentário");
	}

	/**
	 * Fixa UMA publicação no topo do grupo. O `clearPinned` roda na mesma transação: nunca sobra
	 * mais de uma fixada, e a anterior volta ao lugar cronológico dela.
	 */
	@Transactional
	public GroupPost pinPost(String groupId, String postId, String actorId) {
		GroupPost post = requirePost(groupId, postId);
		if (!post.isPublished()) {
			throw new ApiException(404, "POST_NOT_FOUND", "Publicacao nao encontrada neste grupo.");
		}
		require(roleOf(groupId, actorId), GroupCapability.PIN_CONTENT);
		content.clearPinned(groupId);
		GroupPost pinned = post.withPinned(true);
		content.savePost(pinned);
		audit.record(new UserId(actorId), "GROUP_POST_PINNED", postId, "{\"groupId\":\"" + groupId + "\"}");
		return pinned;
	}

	@Transactional
	public GroupPost unpinPost(String groupId, String postId, String actorId) {
		GroupPost post = requirePost(groupId, postId);
		require(roleOf(groupId, actorId), GroupCapability.PIN_CONTENT);
		if (!post.pinned()) {
			return post;
		}
		GroupPost unpinned = post.withPinned(false);
		content.savePost(unpinned);
		audit.record(new UserId(actorId), "GROUP_POST_UNPINNED", postId, "{\"groupId\":\"" + groupId + "\"}");
		return unpinned;
	}

	@Transactional(readOnly = true)
	public Optional<GroupPost> pinnedPost(String groupId, String actorId) {
		requireReadable(groupId);
		requireMember(groupId, actorId);
		return content.findPinned(groupId).filter(GroupPost::isPublished);
	}

	@Transactional
	public GroupComment comment(String groupId, String postId, String authorId, String text, String parentCommentId) {
		// Mesmos portoes do comentario do feed (ECA e sancao de moderacao).
		ageGate.requireCanWrite(authorId);
		sanctionGate.requireNotRestricted(authorId);
		Group group = requireGroup(groupId);
		GroupPost post = requirePost(groupId, postId);
		require(roleOf(groupId, authorId), GroupCapability.INTERACT);
		if (!StringUtils.hasText(text)) {
			throw new ApiException(400, "EMPTY_COMMENT", "Escreva alguma coisa antes de comentar.");
		}
		String clean = PlainText.defuse(text.trim());
		if (clean.length() > GroupComment.MAX_CONTENT) {
			throw new ApiException(400, "CONTENT_TOO_LONG", "Comentario deve ter ate 1.200 caracteres.");
		}
		GroupComment parent = null;
		if (StringUtils.hasText(parentCommentId)) {
			// Resposta só aponta para comentário DESTA publicação (não de outro post nem de outro grupo).
			parent = content.findComment(parentCommentId)
				.filter(item -> item.postId().equals(postId) && item.isPublished())
				.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND",
					"Comentario nao encontrado nesta publicacao."));
		}
		GroupComment comment = GroupComment.create(newId("group_comment"), postId, groupId, authorId,
			parent != null ? parent.id() : null, clean, clock.instant());
		content.saveComment(comment);
		refreshCounters(post);
		notifier.comment(group, post, authorId, parent != null ? parent.authorId() : null);
		return comment;
	}

	/**
	 * Edição do comentário: só o autor edita (quem modera remove, não reescreve o que o outro disse).
	 * Passa pelos mesmos portões do comentar — ECA, sanção de moderação e permissão de interagir.
	 */
	@Transactional
	public GroupComment editComment(String groupId, String postId, String commentId, String actorId, String text) {
		ageGate.requireCanWrite(actorId);
		sanctionGate.requireNotRestricted(actorId);
		requireGroup(groupId);
		requirePost(groupId, postId);
		GroupComment comment = content.findComment(commentId)
			.filter(item -> item.postId().equals(postId) && item.isPublished())
			.orElseThrow(() -> new ApiException(404, "COMMENT_NOT_FOUND",
				"Comentario nao encontrado nesta publicacao."));
		if (!comment.authorId().equals(actorId)) {
			throw new ApiException(403, "FORBIDDEN", "Voce so pode editar o seu proprio comentario.");
		}
		require(roleOf(groupId, actorId), GroupCapability.INTERACT);
		if (!StringUtils.hasText(text)) {
			throw new ApiException(400, "EMPTY_COMMENT", "Escreva alguma coisa antes de comentar.");
		}
		String clean = PlainText.defuse(text.trim());
		if (clean.length() > GroupComment.MAX_CONTENT) {
			throw new ApiException(400, "CONTENT_TOO_LONG", "Comentario deve ter ate 1.200 caracteres.");
		}
		GroupComment edited = comment.edit(clean, clock.instant());
		content.saveComment(edited);
		audit.record(new UserId(actorId), "GROUP_COMMENT_EDITED", commentId,
			"{\"groupId\":\"" + groupId + "\",\"postId\":\"" + postId + "\"}");
		return edited;
	}

	@Transactional(readOnly = true)
	public List<GroupComment> listComments(String groupId, String postId, String actorId, int limit) {
		requireMember(groupId, actorId);
		requirePost(groupId, postId);
		return content.listComments(postId, Math.min(Math.max(limit, 1), 100));
	}

	/**
	 * Alterna a reação: mesma reação de novo remove; outra troca. Uma por pessoa por publicação.
	 *
	 * Devolve `true` quando a reação ficou ativa.
	 */
	@Transactional
	public boolean toggleReaction(String groupId, String postId, String actorId, String type) {
		GroupPost post = requirePost(groupId, postId);
		require(roleOf(groupId, actorId), GroupCapability.INTERACT);
		ReactionType parsed = parseReaction(type);
		boolean active;
		var existing = content.findReaction(postId, actorId);
		if (existing.isPresent() && existing.get().type() == parsed) {
			content.removeReaction(postId, actorId);
			active = false;
		} else {
			content.saveReaction(GroupReaction.create(newId("group_reaction"), postId, actorId, parsed,
				clock.instant()));
			active = true;
		}
		refreshCounters(post);
		return active;
	}

	/** Reações do leitor por publicação — uma consulta só, para a lista não virar N+1. */
	@Transactional(readOnly = true)
	public Map<String, ReactionType> myReactions(String groupId, String actorId, List<String> postIds) {
		requireMember(groupId, actorId);
		return content.reactionsByUser(actorId, postIds);
	}

	/** Remove a reação de vez (o POST alterna; este é o "não quero mais", explícito). */
	@Transactional
	public void removeReaction(String groupId, String postId, String actorId) {
		GroupPost post = requirePost(groupId, postId);
		require(roleOf(groupId, actorId), GroupCapability.INTERACT);
		content.removeReaction(postId, actorId);
		refreshCounters(post);
	}

	private GroupPost requirePost(String groupId, String postId) {
		return content.findPost(postId)
			.filter(post -> post.groupId().equals(groupId))
			.orElseThrow(() -> new ApiException(404, "POST_NOT_FOUND", "Publicacao nao encontrada neste grupo."));
	}

	private void refreshCounters(GroupPost post) {
		content.savePost(post.withCounters(content.countReactions(post.id()), content.countComments(post.id())));
	}

	private static ReactionType parseReaction(String type) {
		try {
			return ReactionType.valueOf(type == null ? "" : type.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException error) {
			throw new ApiException(400, "INVALID_REACTION", "Reacao desconhecida.");
		}
	}

	/**
	 * Grupo encerrado não entrega conteúdo nem a membro — sai da descoberta e some para leitura.
	 * A equipe da plataforma investiga por outro caminho, não por este.
	 */
	private Group requireReadable(String groupId) {
		Group group = requireGroup(groupId);
		if (!group.status().readableByMembers()) {
			throw new ApiException(404, "GROUP_NOT_FOUND", "Grupo nao encontrado.");
		}
		return group;
	}

	private Group requireGroup(String groupId) {
		return groups.findById(groupId)
			.orElseThrow(() -> new ApiException(404, "GROUP_NOT_FOUND", "Grupo nao encontrado."));
	}

	private GroupTopic requireTopic(String groupId, String topicId) {
		return content.findTopic(topicId)
			.filter(topic -> topic.groupId().equals(groupId))
			.orElseThrow(() -> new ApiException(404, "TOPIC_NOT_FOUND", "Topico nao encontrado neste grupo."));
	}

	private GroupRole roleOf(String groupId, String actorId) {
		return groups.findMember(groupId, actorId).map(GroupMember::role).orElse(null);
	}

	private void requireMember(String groupId, String actorId) {
		require(roleOf(groupId, actorId), GroupCapability.VIEW_PRIVATE_CONTENT);
	}

	private void require(GroupRole role, GroupCapability capability) {
		if (!GroupPermissions.can(role, capability)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao tem permissao para isso neste grupo.");
		}
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}

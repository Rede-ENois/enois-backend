package com.socialcoisa.rede.groups.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.feed.domain.ReactionType;
import com.socialcoisa.rede.groups.domain.GroupComment;
import com.socialcoisa.rede.groups.domain.GroupPost;
import com.socialcoisa.rede.groups.domain.GroupReaction;
import com.socialcoisa.rede.groups.domain.GroupTopic;

/**
 * Tópicos e publicações do grupo — conteúdo privado, em tabelas próprias.
 *
 * Nada aqui junta com posts, tags ou mídia do feed principal: o isolamento é estrutural.
 */
public interface GroupContentRepository {

	void saveTopic(GroupTopic topic);

	Optional<GroupTopic> findTopic(String topicId);

	List<GroupTopic> listTopics(String groupId);

	boolean topicNameExists(String groupId, String name);

	/** Próxima posição livre (fim da lista), para o tópico novo nascer no lugar certo. */
	int nextTopicPosition(String groupId);

	void savePost(GroupPost post);

	Optional<GroupPost> findPost(String postId);

	/** Publicações do grupo, opcionalmente presas a um tópico, mais novas primeiro. */
	List<GroupPost> listPosts(String groupId, String topicId, Instant before, int limit);

	/** A publicação fixada no topo do grupo (no máximo uma). */
	Optional<GroupPost> findPinned(String groupId);

	/** Desafixa todas as publicações do grupo — usado antes de fixar a nova. */
	void clearPinned(String groupId);

	void saveComment(GroupComment comment);

	Optional<GroupComment> findComment(String commentId);

	List<GroupComment> listComments(String postId, int limit);

	long countComments(String postId);

	/** Uma reação por pessoa por publicação: salvar substitui a anterior. */
	void saveReaction(GroupReaction reaction);

	Optional<GroupReaction> findReaction(String postId, String userId);

	void removeReaction(String postId, String userId);

	long countReactions(String postId);

	/** Reações do leitor, por publicação — uma consulta só para a lista inteira (sem N+1). */
	Map<String, ReactionType> reactionsByUser(String userId, List<String> postIds);

	/**
	 * Apaga fisicamente o conteúdo do grupo (reações, comentários, publicações e tópicos).
	 *
	 * <p>O caminho normal nunca apaga conteúdo de grupo: remover uma publicação é marcar REMOVED. Esta
	 * remoção existe só para a exclusão FÍSICA pela plataforma, quando manter o histórico não se
	 * aplica. Devolve quantas publicações saíram, para a auditoria.</p>
	 */
	int deleteAllForGroup(String groupId);
}

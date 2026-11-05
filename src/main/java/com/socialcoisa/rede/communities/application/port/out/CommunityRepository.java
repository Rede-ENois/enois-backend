package com.socialcoisa.rede.communities.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.communities.domain.Community;
import com.socialcoisa.rede.communities.domain.CommunityMember;
import com.socialcoisa.rede.communities.domain.MemberRole;

public interface CommunityRepository {

	void save(Community community);

	Optional<Community> findById(String id);

	/** `state`/`cityKey` nulos desligam o filtro de lugar. */
	List<Community> findAll(String topic, String query, String state, String cityKey, Instant before, int limit);

	/** Comunidades da UF: as da cidade primeiro, depois o resto do estado, cada grupo por membros. */
	List<Community> findLocal(String state, String cityKey, int limit);

	/** Comunidades das quais o usuario participa (menu lateral). */
	List<Community> findByMember(String userId, int limit);

	void saveMember(CommunityMember member);

	/** Muda o papel de um membro (transferencia de administracao). */
	void updateMemberRole(String communityId, String userId, MemberRole role);

	/** Remove todos os vinculos de membros (ao excluir a comunidade, evita referencia orfa). */
	void removeAllMembers(String communityId);

	/** Favoritar (bookmark) uma comunidade, independente de participar dela. */
	void addFavorite(String communityId, String userId, Instant createdAt);

	/** Desfavoritar. Retorna true se havia favorito. */
	boolean removeFavorite(String communityId, String userId);

	/** Comunidades favoritadas pelo usuario, mais recentes primeiro. */
	List<Community> findFavorites(String userId, int limit);

	void removeMember(String communityId, String userId);

	boolean isMember(String communityId, String userId);

	int countMembers(String communityId);

	/** Contagem de membros das comunidades da pagina, em UMA consulta (evita N+1 na vitrine). */
	java.util.Map<String, Integer> countMembersByCommunityIds(java.util.Collection<String> communityIds);

	/** Das comunidades informadas, quais a pessoa participa — em UMA consulta. */
	java.util.Set<String> memberCommunityIds(String userId, java.util.Collection<String> communityIds);

	List<CommunityMember> findMembersByCommunity(String communityId);

	Optional<CommunityMember> findMember(String communityId, String userId);

	/** Fixa um post (substitui o anterior da mesma comunidade; a PK garante o único). */
	void savePinnedPost(String communityId, String postId, String pinnedBy, Instant pinnedAt);

	Optional<String> findPinnedPostId(String communityId);

	void removePinnedPost(String communityId);

	void delete(String communityId);
}

package com.socialcoisa.rede.groups.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.groups.domain.Group;
import com.socialcoisa.rede.groups.domain.GroupJoinRequest;
import com.socialcoisa.rede.groups.domain.GroupMember;
import com.socialcoisa.rede.groups.domain.GroupMembership;
import com.socialcoisa.rede.groups.domain.JoinRequestStatus;

/** Persistência dos grupos. Tudo aqui é do domínio de Grupos — nada de comunidades nem de posts. */
public interface GroupRepository {

	void save(Group group);

	Optional<Group> findById(String id);

	Optional<Group> findBySlug(String slug);

	boolean existsBySlug(String slug);

	/**
	 * Grupos que aparecem na descoberta (só metadados públicos), mais novos primeiro, com busca
	 * opcional por nome ou descrição.
	 */
	List<Group> listVisible(String query, Instant before, int limit);

	/**
	 * Grupos em que a pessoa participa, com o papel dela, mais recentes primeiro. Grupo encerrado
	 * fica de fora; o filtro e o limite são da consulta, não da memória.
	 */
	List<GroupMembership> findMemberships(String userId, int limit);

	/** Membros de cada grupo, numa consulta só (grupo sem membro não aparece no mapa). */
	Map<String, Long> countMembers(Collection<String> groupIds);

	void saveMember(GroupMember member);

	Optional<GroupMember> findMember(String groupId, String userId);

	List<GroupMember> listMembers(String groupId);

	long countMembers(String groupId);

	void removeMember(String groupId, String userId);

	/** Mantém o contador desnormalizado do grupo (usado na listagem, para não contar a cada card). */
	void adjustMemberCount(String groupId, int delta);

	void saveJoinRequest(GroupJoinRequest request);

	Optional<GroupJoinRequest> findPendingRequest(String groupId, String userId);

	Optional<GroupJoinRequest> findRequestById(String id);

	List<GroupJoinRequest> listPendingRequests(String groupId);

	/**
	 * Decisão atômica: só muda se o pedido ainda estiver PENDENTE. Devolve 0 quando outra pessoa
	 * decidiu antes — é o que impede duas aprovações simultâneas de virarem duas associações.
	 */
	int markDecided(String requestId, JoinRequestStatus status, String actorId, Instant now);
}

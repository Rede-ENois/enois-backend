package com.socialcoisa.rede.communityprojects.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.communityprojects.domain.CommunityProject;
import com.socialcoisa.rede.communityprojects.domain.CommunityProjectStatus;

public interface CommunityProjectRepository {

	CommunityProject save(CommunityProject project);

	Optional<CommunityProject> findById(String id);

	/** Serializa a decisão de aprovação antes de publicar e gravar o evento. */
	Optional<CommunityProject> findByIdForUpdate(String id);

	Optional<CommunityProject> findByUserAndSlug(String userId, String slug);

	List<CommunityProject> findByUser(String userId);

	List<CommunityProject> findByUserAndStatus(String userId, CommunityProjectStatus status, int limit);

	List<CommunityProject> findByStatus(CommunityProjectStatus status, int limit);

	boolean existsByUserAndSlug(String userId, String slug);

	/** Catálogo publicado com busca e paginação por deslocamento. */
	SearchPage search(String query, String order, int limit, int offset);

	void delete(String id);

	record SearchPage(List<CommunityProject> items, boolean hasMore) {
	}
}

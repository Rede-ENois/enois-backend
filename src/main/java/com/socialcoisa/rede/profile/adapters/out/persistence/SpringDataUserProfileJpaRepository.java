package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserProfileJpaRepository extends JpaRepository<UserProfileJpaEntity, String> {

	List<UserProfileJpaEntity> findByIsAdminTrue();

	Optional<UserProfileJpaEntity> findByUsername(String username);

	Optional<UserProfileJpaEntity> findByEmail(String email);

	boolean existsByUsername(String username);

	boolean existsByEmail(String email);

	@org.springframework.data.jpa.repository.Modifying
	@org.springframework.data.jpa.repository.Query("""
		update UserProfileJpaEntity u
		set u.nameTheme = :theme, u.nameFont = :font, u.nameColor = :color, u.updatedAt = :at
		where u.id = :id
		""")
	int updateNameStyle(@org.springframework.data.repository.query.Param("id") String id,
		@org.springframework.data.repository.query.Param("theme") String theme,
		@org.springframework.data.repository.query.Param("font") String font,
		@org.springframework.data.repository.query.Param("color") String color,
		@org.springframework.data.repository.query.Param("at") java.time.Instant at);

	List<UserProfileJpaEntity> findByOrderByReputationDesc();

	/**
	 * O ranking público é a vitrine de QUEM ACABOU DE CHEGAR: os perfis mais recentes primeiro
	 * (decisão do dono do produto em 2026-10-20). A ordenação é no banco, e não em memória.
	 */
	List<UserProfileJpaEntity> findByOrderByCreatedAtDesc(org.springframework.data.domain.Pageable pageable);

	@org.springframework.data.jpa.repository.Query("""
		select u from UserProfileJpaEntity u
		where lower(u.name) like :q escape '!'
		   or lower(u.username) like :q escape '!'
		   or lower(u.socialName) like :q escape '!'
		order by u.reputation desc
		""")
	List<UserProfileJpaEntity> searchByNameOrUsername(
		@org.springframework.data.repository.query.Param("q") String q,
		org.springframework.data.domain.Pageable page);
}

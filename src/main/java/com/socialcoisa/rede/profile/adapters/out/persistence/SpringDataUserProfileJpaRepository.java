package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserProfileJpaRepository extends JpaRepository<UserProfileJpaEntity, String> {

	List<UserProfileJpaEntity> findByIsAdminTrue();

	@org.springframework.data.jpa.repository.Query("""
		select new com.socialcoisa.rede.profile.application.port.out.AccountIdentity(u.id, u.name, u.username)
		from UserProfileJpaEntity u where u.id in :ids
		""")
	List<com.socialcoisa.rede.profile.application.port.out.AccountIdentity> identitiesOf(
		@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

	@org.springframework.data.jpa.repository.Query("""
		select new com.socialcoisa.rede.profile.application.port.out.SellerIdentity(
			u.id, u.name, u.avatar, u.nameTheme, u.nameFont, u.nameColor, u.nameplate)
		from UserProfileJpaEntity u where u.id in :ids
		""")
	List<com.socialcoisa.rede.profile.application.port.out.SellerIdentity> sellersOf(
		@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

	/**
	 * Uma página da enumeração da réplica de leitura, por chave (criado em, id) — nunca por posição.
	 *
	 * <p>Conta removida fica de fora na consulta: o @ anônimo é gravado com o prefixo fixo, então o
	 * filtro é exato e não precisa de mais uma consulta por item. O `:after*` nulo significa "desde o
	 * começo"; a comparação por par preserva a ordem total mesmo com {@code createdAt} empatado.</p>
	 */
	@org.springframework.data.jpa.repository.Query("""
		select new com.socialcoisa.rede.profile.application.port.out.ReplicaProfileKey(u.id, u.username, u.createdAt)
		from UserProfileJpaEntity u
		where (:afterCreatedAt is null
			or u.createdAt > :afterCreatedAt
			or (u.createdAt = :afterCreatedAt and u.id > :afterId))
		  and u.username not like :removedPrefix
		order by u.createdAt, u.id
		""")
	List<com.socialcoisa.rede.profile.application.port.out.ReplicaProfileKey> replicaPage(
		@org.springframework.data.repository.query.Param("afterCreatedAt") java.time.Instant afterCreatedAt,
		@org.springframework.data.repository.query.Param("afterId") String afterId,
		@org.springframework.data.repository.query.Param("removedPrefix") String removedPrefix,
		org.springframework.data.domain.Pageable page);

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

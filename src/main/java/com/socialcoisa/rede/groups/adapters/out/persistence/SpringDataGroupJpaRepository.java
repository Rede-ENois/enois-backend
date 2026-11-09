package com.socialcoisa.rede.groups.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.socialcoisa.rede.groups.domain.GroupStatus;

interface SpringDataGroupJpaRepository extends JpaRepository<GroupJpaEntity, String> {

	Optional<GroupJpaEntity> findBySlug(String slug);

	boolean existsBySlug(String slug);

	/**
	 * Descoberta: só os grupos visíveis, com busca opcional por nome ou descrição.
	 *
	 * A busca trabalha APENAS com metadados públicos — publicações, membros e tópicos internos não
	 * entram aqui, nem por acidente de junção.
	 */
	@Query("""
		SELECT g FROM GroupJpaEntity g
		WHERE g.status IN :statuses
		  AND (:before IS NULL OR g.createdAt < :before)
		  AND (:query IS NULL OR LOWER(g.name) LIKE :query ESCAPE '!'
		       OR LOWER(g.description) LIKE :query ESCAPE '!')
		ORDER BY g.createdAt DESC""")
	List<GroupJpaEntity> listVisible(@Param("statuses") List<GroupStatus> statuses, @Param("query") String query,
			@Param("before") Instant before, Pageable pageable);

	/**
	 * Ajusta o contador NO BANCO, em uma instrucao: {@code member_count = greatest(0, member_count + :delta)}.
	 *
	 * <p>Antes o ajuste lia a entidade, somava em memoria e gravava a linha inteira. Duas entradas
	 * simultaneas liam o MESMO valor e a segunda sobrescrevia o incremento da primeira — medido: 24
	 * entradas concorrentes deixavam o contador em 1. Como o numero aparece no onboarding (grupos
	 * sugeridos) e ninguem recalcula, o grupo ficava menor do que e para sempre.</p>
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE GroupJpaEntity g SET g.memberCount = CASE WHEN g.memberCount + :delta < 0 THEN 0 "
		+ "ELSE g.memberCount + :delta END WHERE g.id = :groupId")
	int adjustMemberCount(@Param("groupId") String groupId, @Param("delta") int delta);
}

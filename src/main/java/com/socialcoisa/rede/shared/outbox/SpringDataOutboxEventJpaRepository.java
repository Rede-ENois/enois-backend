package com.socialcoisa.rede.shared.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataOutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, String> {

	List<OutboxEventJpaEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(String status,
			Instant nextAttemptAt, Pageable pageable);

	/**
	 * Claim atomico de UM evento pendente. Duas instancias (ou duas rodadas sobrepostas) chamam isso
	 * ao mesmo tempo e so uma recebe 1: a que voltar com 0 pula o evento, sem repetir o efeito. A
	 * condicao de next_attempt_at tambem e conferida aqui, porque entre a leitura e o claim o
	 * evento pode ter sido reagendado.
	 *
	 * <p>A concorrencia e resolvida pelo proprio UPDATE condicional: a segunda transacao espera o
	 * lock da linha e, ao reavaliar o WHERE, encontra claimed_at ja preenchido e devolve 0.</p>
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update OutboxEventJpaEntity e set e.claimedAt = :now "
		+ "where e.id = :id and e.status = :status and e.nextAttemptAt <= :now "
		+ "and (e.claimedAt is null or e.claimedAt < :staleBefore)")
	int claim(@Param("id") String id, @Param("status") String status, @Param("now") Instant now,
			@Param("staleBefore") Instant staleBefore);

	/**
	 * Ids de eventos concluidos antes do corte, para a retencao apagar. A selecao vem antes do delete
	 * (e nao um {@code delete ... limit}) porque o H2 da suite nao aceita LIMIT em DELETE, e porque
	 * assim o lote apagado e exatamente o mesmo entre os dois bancos.
	 */
	@Query("select e.id from OutboxEventJpaEntity e where e.status = :status and e.createdAt < :cutoff "
		+ "order by e.createdAt asc")
	List<String> findIdsByStatusAndCreatedAtBefore(@Param("status") String status, @Param("cutoff") Instant cutoff,
			Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from OutboxEventJpaEntity e where e.id in :ids")
	int deleteByIdIn(@Param("ids") List<String> ids);

	@Query("select count(e) from OutboxEventJpaEntity e where e.status = :status and e.createdAt < :cutoff")
	long countByStatusAndCreatedAtBefore(@Param("status") String status, @Param("cutoff") Instant cutoff);
}

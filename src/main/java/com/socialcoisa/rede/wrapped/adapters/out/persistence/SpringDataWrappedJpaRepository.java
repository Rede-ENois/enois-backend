package com.socialcoisa.rede.wrapped.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * As operações da fila de {@code wrapped_retrospectivas}.
 *
 * <p>O claim é uma atualização condicional (ver docs/CONCURRENCY.md, regra 5): o UPDATE só casa se
 * a linha ainda está PENDING, então duas instâncias que disputem a mesma conta — uma ganha a
 * linha, a outra recebe 0 e busca a próxima. A reserva expira ({@code claim_expira_em}) e a linha
 * volta para PENDING sem perder progresso (a geração é atômica por conta).</p>
 */
interface SpringDataWrappedJpaRepository extends Repository<WrappedRetrospectivaJpaEntity, WrappedRetrospectivaId> {

	Optional<WrappedRetrospectivaJpaEntity> findByUserIdAndAno(String userId, int ano);

	boolean existsByUserIdAndAnoAndEstado(String userId, int ano, String estado);

	/**
	 * Insere a conta na fila só se ela ainda não tem linha no ano ({@code insert ignore}). A
	 * reconstrução da fila é idempotente: quem já está PENDING/PROCESSING/DONE/FAILED não muda.
	 * A forma {@code insert ... select ... where not exists} roda no H2 e no MariaDB.
	 */
	@Modifying
	@Query(value = "insert into wrapped_retrospectivas"
			+ " (user_id, ano, estado, posicao, prioridade, algoritmo_versao, dados_insuficientes, tentativas,"
			+ " criado_em, updated_at)"
			+ " select :userId, :ano, 'PENDING', :posicao, :prioridade, :versao, false, 0, :agora, :agora"
			+ " from dual where not exists (select 1 from wrapped_retrospectivas where user_id = :userId and ano = :ano)",
			nativeQuery = true)
	int inserirPendente(@Param("userId") String userId, @Param("ano") int ano, @Param("posicao") long posicao,
			@Param("prioridade") int prioridade, @Param("versao") int versao, @Param("agora") Instant agora);

	/** A próxima conta da fila, na ordem de prioridade (posição já reflete a credibilidade). */
	@Query("select w.userId from WrappedRetrospectivaJpaEntity w"
			+ " where w.ano = :ano and w.estado = 'PENDING' order by w.posicao, w.userId")
	List<String> primeirasPendentes(@Param("ano") int ano, org.springframework.data.domain.Pageable limite);

	@Modifying
	@Query("update WrappedRetrospectivaJpaEntity w set w.estado = 'PROCESSING', w.iniciadoEm = :agora,"
			+ " w.claimExpiraEm = :expira, w.updatedAt = :agora"
			+ " where w.userId = :userId and w.ano = :ano and w.estado = 'PENDING'")
	int claim(@Param("userId") String userId, @Param("ano") int ano, @Param("agora") Instant agora,
			@Param("expira") Instant expira);

	/** PROCESSING cuja reserva venceu (a instância caiu no meio): volta para a fila. */
	@Modifying
	@Query("update WrappedRetrospectivaJpaEntity w set w.estado = 'PENDING', w.claimExpiraEm = null,"
			+ " w.updatedAt = :agora where w.ano = :ano and w.estado = 'PROCESSING'"
			+ " and w.claimExpiraEm < :agora")
	int expirarReservas(@Param("ano") int ano, @Param("agora") Instant agora);

	@Modifying
	@Query("update WrappedRetrospectivaJpaEntity w set w.estado = 'DONE', w.resultado = :resultado,"
			+ " w.dadosInsuficientes = :dadosInsuficientes, w.falha = null, w.claimExpiraEm = null,"
			+ " w.concluidoEm = :agora, w.updatedAt = :agora"
			+ " where w.userId = :userId and w.ano = :ano and w.estado = 'PROCESSING'")
	int concluir(@Param("userId") String userId, @Param("ano") int ano, @Param("resultado") String resultado,
			@Param("dadosInsuficientes") boolean dadosInsuficientes, @Param("agora") Instant agora);

	@Modifying
	@Query("update WrappedRetrospectivaJpaEntity w set w.estado = 'FAILED', w.falha = :falha,"
			+ " w.tentativas = w.tentativas + 1, w.claimExpiraEm = null, w.updatedAt = :agora"
			+ " where w.userId = :userId and w.ano = :ano and w.estado = 'PROCESSING'")
	int falhar(@Param("userId") String userId, @Param("ano") int ano, @Param("falha") String falha,
			@Param("agora") Instant agora);

	@Query("select count(w) from WrappedRetrospectivaJpaEntity w where w.ano = :ano and w.estado = :estado")
	long contar(@Param("ano") int ano, @Param("estado") String estado);
}

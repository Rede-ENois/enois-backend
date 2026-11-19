package com.socialcoisa.rede.desafios.adapters.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.desafios.application.port.out.DesafiosRepository;
import com.socialcoisa.rede.desafios.domain.Desafio;
import com.socialcoisa.rede.desafios.domain.DesafioStatus;
import com.socialcoisa.rede.desafios.domain.Participacao;
import com.socialcoisa.rede.desafios.domain.ParticipacaoStatus;

@Component
@Transactional
class DesafiosPersistenceAdapter implements DesafiosRepository {

	private final SpringDataDesafioJpaRepository desafios;
	private final SpringDataParticipacaoJpaRepository participacoes;
	private final SpringDataDesafioCheerJpaRepository cheers;
	private final Clock clock;

	DesafiosPersistenceAdapter(SpringDataDesafioJpaRepository desafios,
			SpringDataParticipacaoJpaRepository participacoes, SpringDataDesafioCheerJpaRepository cheers,
			Clock clock) {
		this.desafios = desafios;
		this.participacoes = participacoes;
		this.cheers = cheers;
		this.clock = clock;
	}

	@Override
	public Desafio saveDesafio(Desafio desafio) {
		return desafios.save(new DesafioJpaEntity(desafio)).toDomain();
	}

	@Override
	public Optional<Desafio> findDesafioById(String id) {
		return desafios.findById(id).map(DesafioJpaEntity::toDomain);
	}

	@Override
	public List<Desafio> findFeedBefore(Instant before, int limit) {
		return desafios.feedBefore(DesafioStatus.REMOVED, before, PageRequest.of(0, limit)).stream()
			.map(DesafioJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<Desafio> findActiveByAuthor(String authorId) {
		return desafios.ativosDoAutor(authorId, DesafioStatus.ACTIVE, PageRequest.of(0, 50)).stream()
			.map(DesafioJpaEntity::toDomain)
			.toList();
	}

	@Override
	public Participacao saveParticipacao(Participacao participacao) {
		return participacoes.save(new ParticipacaoJpaEntity(participacao)).toDomain();
	}

	@Override
	public Optional<Participacao> findParticipacaoById(String id) {
		return participacoes.findById(id).map(ParticipacaoJpaEntity::toDomain);
	}

	@Override
	public Optional<Participacao> findParticipacaoDoAutor(String desafioId, String authorId) {
		return participacoes
			.findByDesafioIdAndAuthorIdAndStatus(desafioId, authorId, ParticipacaoStatus.ACTIVE)
			.map(ParticipacaoJpaEntity::toDomain);
	}

	@Override
	public List<Participacao> findActiveParticipacoesByDesafio(String desafioId) {
		return participacoes.findByDesafioIdAndStatusOrderByCreatedAtAsc(desafioId, ParticipacaoStatus.ACTIVE)
			.stream()
			.map(ParticipacaoJpaEntity::toDomain)
			.toList();
	}

	@Override
	public long countActiveParticipacoes(String desafioId) {
		return participacoes.countByDesafioIdAndStatus(desafioId, ParticipacaoStatus.ACTIVE);
	}

	@Override
	public void cheer(String participacaoId, String userId) {
		cheers.save(new DesafioCheerJpaEntity(participacaoId, userId, clock.instant()));
	}

	@Override
	public void uncheer(String participacaoId, String userId) {
		cheers.deleteByParticipacaoIdAndUserId(participacaoId, userId);
	}

	@Override
	public Map<String, Long> countCheersByParticipacaoIds(List<String> participacaoIds) {
		if (participacaoIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Long> totais = new HashMap<>();
		for (SpringDataDesafioCheerJpaRepository.CheerCountProjection linha : cheers
			.countByParticipacaoIdIn(participacaoIds)) {
			totais.put(linha.getParticipacaoId(), linha.getTotal());
		}
		return totais;
	}

	@Override
	public Set<String> findUserCheers(String userId, Set<String> participacaoIds) {
		if (participacaoIds.isEmpty()) {
			return Set.of();
		}
		return cheers.findByUserIdAndParticipacaoIdIn(userId, participacaoIds).stream()
			.map(DesafioCheerJpaEntity::participacaoId)
			.collect(java.util.stream.Collectors.toSet());
	}
}

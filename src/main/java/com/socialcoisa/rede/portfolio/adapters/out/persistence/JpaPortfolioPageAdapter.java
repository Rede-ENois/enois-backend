package com.socialcoisa.rede.portfolio.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.portfolio.application.port.out.PortfolioPageRepository;
import com.socialcoisa.rede.portfolio.domain.PortfolioPage;

/**
 * A página do portfólio, uma linha por dono. Leitura-decisão-escrita dentro da transação do
 * serviço: a linha é do dono (chave = usuário), então não há concorrência entre contas; a mesma
 * conta só tem uma requisição de edição por vez no editor.
 */
@Repository
class JpaPortfolioPageAdapter implements PortfolioPageRepository {

	private final SpringDataPortfolioPageJpaRepository jpaRepository;

	JpaPortfolioPageAdapter(SpringDataPortfolioPageJpaRepository jpaRepository) {
		this.jpaRepository = jpaRepository;
	}

	@Override
	public Optional<PortfolioPage> find(String userId) {
		return jpaRepository.findById(userId).map(PortfolioPageJpaEntity::toDomain);
	}

	@Override
	public PortfolioPage save(PortfolioPage page) {
		return jpaRepository.save(PortfolioPageJpaEntity.fromDomain(page)).toDomain();
	}

	@Override
	public List<PortfolioPage> findPublished(Instant before, String beforeId, int limit) {
		return jpaRepository.findPublished(before, beforeId, PageRequest.of(0, Math.max(1, limit))).stream()
			.map(PortfolioPageJpaEntity::toDomain)
			.toList();
	}
}

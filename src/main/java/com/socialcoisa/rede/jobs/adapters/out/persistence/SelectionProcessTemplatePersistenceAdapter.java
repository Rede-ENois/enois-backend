package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.jobs.application.port.out.SelectionProcessTemplateRepository;
import com.socialcoisa.rede.jobs.domain.SelectionProcessTemplate;

@Component
@Transactional
class SelectionProcessTemplatePersistenceAdapter implements SelectionProcessTemplateRepository {

	private final SpringDataSelectionProcessTemplateJpaRepository jpa;

	SelectionProcessTemplatePersistenceAdapter(SpringDataSelectionProcessTemplateJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public SelectionProcessTemplate save(SelectionProcessTemplate template) {
		return jpa.save(SelectionProcessTemplateJpaEntity.fromDomain(template)).toDomain();
	}

	@Override
	public Optional<SelectionProcessTemplate> findById(String id) {
		return jpa.findById(id).map(SelectionProcessTemplateJpaEntity::toDomain);
	}

	@Override
	public List<SelectionProcessTemplate> findByOwner(String ownerId, int limit) {
		return jpa.findPageByOwner(ownerId, PageRequest.of(0, Math.clamp(limit, 1, 100))).stream()
			.map(SelectionProcessTemplateJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void deleteById(String id) {
		jpa.deleteById(id);
	}
}

package com.socialcoisa.rede.portfolio.adapters.out.catalogs;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.socialcoisa.rede.portfolio.application.port.out.PortfolioProjectCatalog;
import com.socialcoisa.rede.projects.application.port.out.UserProjectRepository;

/** Quais cards de projeto são do dono, para o bloco de projeto só referenciar o que é dele. */
@Component
class UserProjectPortfolioCatalogAdapter implements PortfolioProjectCatalog {

	private final UserProjectRepository projects;

	UserProjectPortfolioCatalogAdapter(UserProjectRepository projects) {
		this.projects = projects;
	}

	@Override
	public Set<String> projectIdsOf(String userId) {
		return projects.findByUser(userId).stream().map(project -> project.id()).collect(Collectors.toSet());
	}
}

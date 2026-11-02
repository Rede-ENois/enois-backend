package com.socialcoisa.rede.companies.adapters.out.moderation;

import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.companies.application.CompanyService;
import com.socialcoisa.rede.moderation.application.port.out.CompanyRemovalPort;
import com.socialcoisa.rede.moderation.application.port.out.CompanyReportReader;

/**
 * Pagina empresarial para a triagem e a revisao de denuncias.
 *
 * O conteudo e o que a pagina diz sobre si (nome, setor, descricao, local, site) — e o que dá
 * para julgar. Pagina que nao existe (ou ja fechada) devolve vazio. O "autor" e o dono da pagina.
 */
@Component
class CompanyModerationAdapter implements CompanyReportReader, CompanyRemovalPort {

	/** Teto do que vai ao modelo/ao painel: o suficiente para julgar, sem estourar o prompt. */
	private static final int MAX_CHARS = 4_000;

	private final CompanyService companies;

	CompanyModerationAdapter(CompanyService companies) {
		this.companies = companies;
	}

	@Override
	public Optional<String> contentOf(String companyId) {
		return companies.findById(companyId).map(company -> Stream
			.of(company.name(), company.sector(), company.location(), company.website(), company.description())
			.filter(StringUtils::hasText)
			.reduce((first, second) -> first + "\n" + second)
			.map(this::shorten)
			.filter(StringUtils::hasText))
			.orElse(Optional.empty());
	}

	@Override
	public Optional<String> ownerOf(String companyId) {
		return companies.findById(companyId).map(company -> company.ownerUserId())
			.filter(StringUtils::hasText);
	}

	@Override
	public boolean remove(String companyId, String reviewerId) {
		return companies.closeByModeration(companyId, reviewerId);
	}

	private String shorten(String content) {
		String trimmed = content.trim();
		return trimmed.length() <= MAX_CHARS ? trimmed : trimmed.substring(0, MAX_CHARS);
	}
}

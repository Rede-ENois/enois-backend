package com.socialcoisa.rede.jobs.adapters.out.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.jobs.application.port.out.CompanyDirectoryReader;

/**
 * Diretorio de empresas derivado das vagas: internas (abertas) + externas agregadas por nome,
 * sem diferenciar maiusculas/minusculas. Sem entidade Company ainda — quando a Fase 1 do
 * COMPANY_PAGES existir, o diretorio passa a ler de companies.
 */
@Repository
class CompanyDirectoryReaderAdapter implements CompanyDirectoryReader {

	private final SpringDataJobJpaRepository jobs;
	private final SpringDataExternalJobJpaRepository externalJobs;

	CompanyDirectoryReaderAdapter(SpringDataJobJpaRepository jobs, SpringDataExternalJobJpaRepository externalJobs) {
		this.jobs = jobs;
		this.externalJobs = externalJobs;
	}

	@Override
	public List<CompanySummary> directory(String query, int limit) {
		Map<String, long[]> totalsEnois = new HashMap<>();
		Map<String, String> displayEnois = new LinkedHashMap<>();
		Map<String, String> logosEnois = new HashMap<>();
		merge(jobs.countOpenJobsByCompany(), totalsEnois, displayEnois, logosEnois);

		Map<String, long[]> totalsExternas = new HashMap<>();
		Map<String, String> displayExternas = new LinkedHashMap<>();
		Map<String, String> logosExternas = new HashMap<>();
		merge(externalJobs.countJobsByCompany(), totalsExternas, displayExternas, logosExternas);

		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<CompanySummary> all = new ArrayList<>();
		for (var key : displayEnois.keySet()) {
			if (!aceita(needle, key, displayEnois.get(key))) {
				continue;
			}
			all.add(new CompanySummary(displayEnois.get(key), logosEnois.get(key), totalsEnois.get(key)[0], "enois"));
		}
		for (var key : displayExternas.keySet()) {
			if (!aceita(needle, key, displayExternas.get(key))) {
				continue;
			}
			long[] agg = totalsExternas.get(key);
			// nome repetido entre as origens: as vagas somam, a origem fica como externa
			if (totalsEnois.containsKey(key)) {
				totalsEnois.get(key)[0] += agg[0];
			}
			all.add(new CompanySummary(displayExternas.get(key), logosExternas.getOrDefault(key, ""), agg[0],
				"externa"));
		}
		all.sort((a, b) -> Long.compare(b.openJobs(), a.openJobs()));
		return all.subList(0, Math.min(limit, all.size()));
	}

	@Override
	public List<NamedCount> openUnlinkedJobsByName() {
		return byName(jobs.countOpenUnlinkedJobsByCompany());
	}

	@Override
	public List<NamedCount> externalJobsByName() {
		return byName(externalJobs.countJobsByCompany());
	}

	@Override
	public Map<String, Long> openJobsByCompanyIds(java.util.Collection<String> companyIds) {
		Map<String, Long> counts = new HashMap<>();
		if (!companyIds.isEmpty()) {
			for (Object[] row : jobs.countOpenByCompanyIds(companyIds)) {
				counts.put((String) row[0], ((Number) row[1]).longValue());
			}
		}
		return counts;
	}

	@Override
	public List<PageJob> openJobsOf(String companyId, int limit) {
		return jobs.findOpenByCompanyId(companyId, org.springframework.data.domain.PageRequest.of(0, limit)).stream()
			.map(JobJpaEntity::toDomain)
			.map(j -> new PageJob(j.id(), j.title(), j.location(), j.type(), j.level(), j.postedAt()))
			.toList();
	}

	private List<NamedCount> byName(List<Object[]> rows) {
		Map<String, long[]> totals = new HashMap<>();
		Map<String, String> display = new LinkedHashMap<>();
		Map<String, String> logos = new HashMap<>();
		merge(rows, totals, display, logos);
		return display.entrySet().stream()
			.map(e -> new NamedCount(e.getValue(), logos.get(e.getKey()), totals.get(e.getKey())[0]))
			.toList();
	}

	private boolean aceita(String needle, String key, String display) {
		return needle.isEmpty() || key.contains(needle) || display.toLowerCase(Locale.ROOT).contains(needle);
	}

	private void merge(List<Object[]> rows, Map<String, long[]> totals, Map<String, String> display,
			Map<String, String> logos) {
		for (Object[] row : rows) {
			String name = String.valueOf(row[0]);
			String key = name.toLowerCase(Locale.ROOT);
			long count = ((Number) row[1]).longValue();
			totals.computeIfAbsent(key, k -> new long[1])[0] += count;
			display.putIfAbsent(key, name);
			if (row.length > 2 && row[2] != null) {
				logos.putIfAbsent(key, String.valueOf(row[2]));
			}
		}
	}
}

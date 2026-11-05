package com.socialcoisa.rede.companies.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.companies.domain.Company;
import com.socialcoisa.rede.jobs.application.port.out.CompanyDirectoryReader;

/**
 * The companies directory: real pages first-class, plus the names that only exist on jobs.
 * PAGE = an active page (its counts come from grouped reads, never one query per page);
 * JOBS = open internal jobs not linked to any page, under a name no page has;
 * EXTERNAL = external job names no page and no internal job name has.
 */
@Service
public class CompanyDirectoryService {

	public enum Kind {
		PAGE, JOBS, EXTERNAL
	}

	public record Entry(Kind kind, String slug, String name, String logo, String sector, String location,
			long openJobs, long openProjects, long followers) {
	}

	/** How many pages are read before ranking; ranking needs the counts, so it happens in memory. */
	private static final int PAGE_CANDIDATES = 500;

	private final CompanyService companies;
	private final MicroProjectService microProjects;
	private final CompanyDirectoryReader jobsDirectory;

	public CompanyDirectoryService(CompanyService companies, MicroProjectService microProjects,
			CompanyDirectoryReader jobsDirectory) {
		this.companies = companies;
		this.microProjects = microProjects;
		this.jobsDirectory = jobsDirectory;
	}

	@Transactional(readOnly = true)
	public List<Entry> directory(String query, String sector, int limit) {
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		boolean bySector = sector != null && !sector.isBlank();

		List<Company> pages = companies.active(query, sector, PAGE_CANDIDATES);
		List<String> ids = pages.stream().map(Company::id).toList();
		Map<String, Long> jobs = jobsDirectory.openJobsByCompanyIds(ids);
		Map<String, Long> projects = microProjects.openCountsByCompany(ids);
		Map<String, Long> followers = companies.followerCounts(ids);
		List<Entry> entries = new ArrayList<>();
		for (Company page : pages) {
			entries.add(new Entry(Kind.PAGE, page.slug(), page.name(), page.logo(), page.sector(), page.location(),
				jobs.getOrDefault(page.id(), 0L), projects.getOrDefault(page.id(), 0L),
				followers.getOrDefault(page.id(), 0L)));
		}

		// Names without a page have no sector: a sector filter shows pages only.
		if (!bySector) {
			Set<String> pageNames = new HashSet<>();
			companies.activeNames().forEach(name -> pageNames.add(key(name)));
			Map<String, Entry> byName = new LinkedHashMap<>();
			for (var job : jobsDirectory.openUnlinkedJobsByName()) {
				if (!pageNames.contains(key(job.name())) && matches(needle, job.name())) {
					byName.put(key(job.name()), new Entry(Kind.JOBS, null, job.name(), job.logo(), null, null,
						job.count(), 0, 0));
				}
			}
			for (var external : jobsDirectory.externalJobsByName()) {
				String k = key(external.name());
				if (pageNames.contains(k) || !matches(needle, external.name())) {
					continue;
				}
				Entry internal = byName.get(k);
				// The same name on internal and external jobs is one company: the counts add up.
				byName.put(k, internal != null
					? new Entry(Kind.JOBS, null, internal.name(), internal.logo(), null, null,
						internal.openJobs() + external.count(), 0, 0)
					: new Entry(Kind.EXTERNAL, null, external.name(), external.logo(), null, null, external.count(), 0,
						0));
			}
			entries.addAll(byName.values());
		}

		// Pages first (the companies that are on the network), then names from jobs; each by activity.
		entries.sort(Comparator.comparing((Entry e) -> e.kind() == Kind.PAGE ? 0 : 1)
			.thenComparingLong(e -> -(e.openJobs() + e.openProjects()))
			.thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
		return entries.subList(0, Math.min(limit, entries.size()));
	}

	private static String key(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	private static boolean matches(String needle, String name) {
		return needle.isEmpty() || key(name).contains(needle);
	}
}

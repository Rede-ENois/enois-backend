package com.socialcoisa.rede.jobs.application.port.out;

import java.util.List;

/** Leitura do diretorio de empresas derivado das vagas (internas + externas). */
public interface CompanyDirectoryReader {

	record CompanySummary(String name, String logo, long openJobs, String origem) {
	}

	/** As duas origens do diretório (a aba do front separa Énóis × plataformas externas). */
	String ORIGEM_ENOIS = "enois";
	String ORIGEM_EXTERNA = "externa";

	List<CompanySummary> directory(String query, int limit);

	/** A company name seen on jobs, case variants merged, with how many jobs carry it. */
	record NamedCount(String name, String logo, long count) {
	}

	/** An open job of a company page, as the page lists it. */
	record PageJob(String id, String title, String location, String type, String level,
			java.time.Instant postedAt) {
	}

	/** Open internal jobs NOT linked to a page, grouped by company name. */
	List<NamedCount> openUnlinkedJobsByName();

	/** External jobs grouped by company name. */
	List<NamedCount> externalJobsByName();

	/** Open jobs linked to each of these pages. */
	java.util.Map<String, Long> openJobsByCompanyIds(java.util.Collection<String> companyIds);

	/** Open jobs linked to a page, newest first. */
	List<PageJob> openJobsOf(String companyId, int limit);
}

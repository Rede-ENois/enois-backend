package com.socialcoisa.rede.jobs.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.companies.application.CompanyService;
import com.socialcoisa.rede.companies.application.RecruitmentTeamService;
import com.socialcoisa.rede.companies.domain.RecruitmentCapability;
import com.socialcoisa.rede.jobs.application.port.out.TalentPoolRepository;
import com.socialcoisa.rede.jobs.domain.TalentPoolOptIn;
import com.socialcoisa.rede.profile.application.port.out.TalentCandidateReader;
import com.socialcoisa.rede.profile.application.port.out.TalentCandidateReader.TalentCandidate;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.blocks.BlockedUsersReader;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Banco de talentos: a candidatura que a pessoa libera para uma organização continuar vendo depois
 * que a vaga fecha.
 *
 * <p>O que sustenta tudo é o consentimento explícito, guardado por par (pessoa, organização): sem
 * a linha ativa, a pessoa não é encontrada por busca nenhuma. Retirar não apaga o histórico de uma
 * vez — a leitura do recrutamento deixa de vê-la no mesmo instante, e o registro só é expurgado
 * depois do prazo de guarda, porque o fato de ter consentido (e retirado) precisa continuar
 * auditável.
 */
@Service
public class TalentPoolService {

	/** Quanto tempo a linha retirada fica guardada antes do expurgo — a prova do consentimento. */
	static final Duration RETENTION = Duration.ofDays(180);

	/** Teto da página: recrutamento é lista para ler, não exportação em massa. */
	static final int MAX_PAGE_SIZE = 50;

	private final TalentPoolRepository pool;
	private final CompanyService companies;
	private final RecruitmentTeamService team;
	private final TalentCandidateReader candidates;
	private final BlockedUsersReader blocks;
	private final AuditTrail audit;
	private final Clock clock;

	public TalentPoolService(TalentPoolRepository pool, CompanyService companies, RecruitmentTeamService team,
			TalentCandidateReader candidates, BlockedUsersReader blocks, AuditTrail audit, Clock clock) {
		this.pool = pool;
		this.companies = companies;
		this.team = team;
		this.candidates = candidates;
		this.blocks = blocks;
		this.audit = audit;
		this.clock = clock;
	}

	// ---------- O lado do candidato ----------

	/** O que a pessoa liberou, com o nome da organização (ela precisa saber onde está listada). */
	public List<OptInView> mine(String candidateId) {
		List<TalentPoolOptIn> mine = pool.activeOfCandidate(candidateId);
		if (mine.isEmpty()) {
			return List.of();
		}
		Map<String, String> names = companyNames(mine.stream().map(TalentPoolOptIn::companyId).toList());
		return mine.stream().map(optIn -> OptInView.of(optIn, names.get(optIn.companyId()))).toList();
	}

	public record OptInView(String companyId, String companyName, String consentVersion, String consentedAt,
			String note) {

		static OptInView of(TalentPoolOptIn optIn, String companyName) {
			return new OptInView(optIn.companyId(), companyName, optIn.consentVersion(),
				optIn.consentedAt().toString(), optIn.note());
		}
	}

	/**
	 * Libera a candidatura para uma organização. Idempotente: liberar de novo a mesma organização
	 * renova a data e a versão do consentimento, sem criar linha duplicada.
	 */
	@Transactional
	public OptInView optIn(String candidateId, String companyId) {
		String organization = requireCompany(companyId);
		Instant now = clock.instant();
		TalentPoolOptIn existing = pool.findByCandidateAndCompany(candidateId, organization).orElse(null);
		TalentPoolOptIn target = existing == null ? TalentPoolOptIn.create(candidateId, organization, now)
			: existing.active() ? existing : existing.reopen(now);
		TalentPoolOptIn saved = pool.save(target);
		record("RECRUITMENT_TALENT_POOL_OPT_IN", candidateId, organization,
			Map.of("consentVersion", saved.consentVersion()));
		return OptInView.of(saved, companyNames(List.of(saved.companyId())).get(saved.companyId()));
	}

	/**
	 * Retira a liberação. Silenciosa quando não havia nada: o estado desejado é "não estou no
	 * banco", e esse estado já vale — recusar seria inventar um erro para quem só quer sair.
	 */
	@Transactional
	public void withdraw(String candidateId, String companyId) {
		String organization = requireCompany(companyId);
		pool.findByCandidateAndCompany(candidateId, organization)
			.filter(TalentPoolOptIn::active)
			.ifPresent(existing -> {
				pool.save(existing.withdraw(clock.instant()));
				record("RECRUITMENT_TALENT_POOL_WITHDRAWN", candidateId, organization, Map.of());
			});
	}

	/** Grava o recado para os recrutadores. Não cria liberação: sem opt-in antes, é 404. */
	@Transactional
	public OptInView note(String candidateId, String companyId, String note) {
		String organization = requireCompany(companyId);
		TalentPoolOptIn existing = pool.findByCandidateAndCompany(candidateId, organization)
			.filter(TalentPoolOptIn::active)
			.orElseThrow(() -> new ApiException(404, "NOT_IN_TALENT_POOL",
				"Você não liberou sua candidatura para esta organização."));
		TalentPoolOptIn saved = pool.save(existing.withNote(note, clock.instant()));
		return OptInView.of(saved, companyNames(List.of(organization)).get(organization));
	}

	// ---------- O lado da organização ----------

	/**
	 * A página do banco de talentos da organização, com a busca aplicada.
	 *
	 * <p>Duas etapas, de propósito: primeiro os consentimentos ativos (a fonte da verdade sobre
	 * quem pode aparecer), depois a busca entre eles. Filtrar a busca pelo conjunto é o que faz o
	 * consentimento valer para qualquer critério — a ordem inversa deixaria a busca decidir quem
	 * aparece.
	 */
	public TalentPage page(String companyId, String requesterId, String cursor, String search, int limit) {
		String organization = requireRecruiter(companyId, requesterId);
		int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
		Cursor decoded = Cursor.decode(cursor);
		// A leitura de consentimentos vale uma linha a mais que a página final: o conjunto de
		// exibição pode perder linhas (bloqueio) e precisa continuar cheio.
		List<TalentPoolOptIn> optedIn = pool.page(organization, decoded.instant(), decoded.id(), size + 1);
		boolean hasMore = optedIn.size() > size;
		List<TalentPoolOptIn> window = hasMore ? optedIn.subList(0, size) : optedIn;

		List<String> consentedIds = window.stream().map(TalentPoolOptIn::candidateId).toList();
		Map<String, String> notes = new LinkedHashMap<>();
		window.forEach(optIn -> notes.put(optIn.candidateId(), optIn.note()));

		List<String> visibleIds = visibleIds(requesterId, consentedIds, search);
		List<TalentRow> rows = new ArrayList<>();
		for (TalentCandidate candidate : orderBy(visibleIds, candidates.byIds(visibleIds))) {
			rows.add(new TalentRow(candidate.userId(), candidate.displayName(), candidate.username(),
				candidate.headline(), candidate.avatar(), candidate.location(), candidate.resumeName(),
				candidate.resumeUrl(), candidate.experienceCount(), notes.get(candidate.userId())));
		}
		TalentPoolOptIn last = window.isEmpty() ? null : window.get(window.size() - 1);
		String nextCursor = hasMore && last != null ? Cursor.encode(last.consentedAt(), last.id()) : null;
		return new TalentPage(rows, nextCursor, pool.countActive(organization));
	}

	public record TalentPage(List<TalentRow> candidates, String nextCursor, int totalOptedIn) {
	}

	/** A linha do banco de talentos: o que o recrutamento vê de cada pessoa liberada. */
	public record TalentRow(String userId, String name, String username, String headline, String avatar,
			String location, String resumeName, String resumeUrl, int experienceCount, String note) {
	}

	/** Quem, entre as pessoas liberadas, o recrutador pode realmente ver nesta consulta. */
	private List<String> visibleIds(String requesterId, List<String> candidateIds, String search) {
		if (candidateIds.isEmpty()) {
			return List.of();
		}
		Set<String> hidden = blocks.hiddenIdsFor(requesterId);
		List<String> allowed = candidateIds.stream().filter(id -> !hidden.contains(id)).toList();
		if (!StringUtils.hasText(search)) {
			return allowed;
		}
		return candidates.searchAmong(allowed, search, Math.max(1, allowed.size()));
	}

	/**
	 * A busca devolve na ordem de relevância do banco; a lista precisa da ordem dos consentimentos
	 * para o cursor continuar coerente com o que foi entregue.
	 */
	private static List<TalentCandidate> orderBy(List<String> order, List<TalentCandidate> found) {
		Map<String, TalentCandidate> byId = new LinkedHashMap<>();
		found.forEach(candidate -> byId.put(candidate.userId(), candidate));
		List<TalentCandidate> ordered = new ArrayList<>();
		for (String id : order) {
			TalentCandidate candidate = byId.get(id);
			if (candidate != null) {
				ordered.add(candidate);
			}
		}
		return ordered;
	}

	/** Quem recruta na organização conduz; quem não, recebe 403 — nunca uma lista vazia silenciosa. */
	private String requireRecruiter(String companyId, String requesterId) {
		String organization = requireCompany(companyId);
		if (!team.can(organization, requesterId, RecruitmentCapability.VIEW_CANDIDATES)) {
			throw new ApiException(403, "RECRUITMENT_FORBIDDEN",
				"Só quem conduz o recrutamento desta organização vê o banco de talentos.");
		}
		return organization;
	}

	/**
	 * A organização existe e está ativa. Página encerrada não recebe consentimento novo: liberar a
	 * própria candidatura para uma página que não existe seria consentir com o vazio.
	 */
	private String requireCompany(String companyId) {
		if (!StringUtils.hasText(companyId)) {
			throw new ApiException(400, "COMPANY_REQUIRED", "Informe a organização.");
		}
		var company = companies.company(companyId.trim());
		if (!company.active()) {
			throw new ApiException(404, "COMPANY_NOT_FOUND", "Organização não encontrada.");
		}
		return company.id();
	}

	// ---------- LGPD ----------

	/**
	 * A exclusão da conta leva junto o consentimento do banco de talentos. Chamado pelo módulo de
	 * identidade: a pessoa pediu para sair de tudo, e "tudo" inclui a lista que ela liberou.
	 */
	@Transactional
	public void forgetCandidate(String candidateId) {
		pool.purgeCandidate(candidateId);
	}

	/** Expurgo das linhas retiradas além do prazo de guarda. Devolve quantas saíram. */
	@Transactional
	public int purgeWithdrawn() {
		int purged = pool.purgeWithdrawnBefore(clock.instant().minus(RETENTION));
		if (purged > 0) {
			audit.record("system", "RECRUITMENT_TALENT_POOL_PURGED", "talent_pool",
				"{\"purged\":" + purged + "}");
		}
		return purged;
	}

	private void record(String action, String actorId, String companyId, Map<String, String> extra) {
		Map<String, String> metadata = new LinkedHashMap<>(extra);
		metadata.put("companyId", companyId);
		audit.record(actorId, action, "talent_pool:" + companyId, json(metadata));
	}

	private static String json(Map<String, String> metadata) {
		StringBuilder out = new StringBuilder("{");
		metadata.forEach((key, value) -> {
			if (out.length() > 1) {
				out.append(',');
			}
			out.append('"').append(key).append("\":\"").append(escape(value)).append('"');
		});
		return out.append('}').toString();
	}

	private static String escape(String value) {
		return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** O cursor carrega o par (instante, id): o instante sozinho repetiria as linhas empatadas. */
	record Cursor(Instant instant, String id) {

		static Cursor decode(String raw) {
			if (!StringUtils.hasText(raw)) {
				return new Cursor(null, null);
			}
			try {
				String payload = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
				int separator = payload.indexOf('|');
				if (separator <= 0) {
					throw new IllegalArgumentException("cursor sem desempate");
				}
				return new Cursor(Instant.parse(payload.substring(0, separator)), payload.substring(separator + 1));
			}
			catch (RuntimeException invalid) {
				throw new ApiException(400, "INVALID_CURSOR", "Cursor inválido.");
			}
		}

		static String encode(Instant instant, String id) {
			String payload = instant + "|" + id;
			return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		}
	}

	/** As organizações de um conjunto de ids, em uma consulta — o nome que a linha do candidato mostra. */
	Map<String, String> companyNames(Collection<String> companyIds) {
		Map<String, String> names = new LinkedHashMap<>();
		for (var company : companies.byIds(companyIds)) {
			names.put(company.id(), company.name());
		}
		return names;
	}
}

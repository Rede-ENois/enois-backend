package com.socialcoisa.rede.talentbank.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase;
import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase.CertificationView;
import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase.EducationView;
import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase.ExperienceView;
import com.socialcoisa.rede.profile.application.port.out.TalentCandidateReader;
import com.socialcoisa.rede.profile.application.port.out.TalentCandidateReader.TalentCandidate;
import com.socialcoisa.rede.profile.application.port.out.TalentCandidateReader.TalentCandidateFilter;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEnrollmentRepository;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankEventRepository;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankResumeReader;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankResumeReader.ResumeFile;
import com.socialcoisa.rede.talentbank.domain.EnrollmentState;
import com.socialcoisa.rede.talentbank.domain.SharedField;
import com.socialcoisa.rede.talentbank.domain.TalentBank;
import com.socialcoisa.rede.talentbank.domain.TalentBankEnrollment;
import com.socialcoisa.rede.talentbank.domain.TalentBankEvent;
import com.socialcoisa.rede.talentbank.domain.TalentBankEvent.Action;

/**
 * O painel do recrutador: quem se inscreveu, o que cada pessoa autorizou e o que dá para ver.
 *
 * <p>Duas travas em toda leitura: a banca precisa ser do dono que está perguntando (a de outro é
 * 404), e o dado só aparece quando a inscrição está ATIVA e o campo está entre os autorizados. Nada
 * de acesso "irrestrito à conta" — e o currículo sai pelo backend, nunca pelo endereço público do
 * storage.</p>
 */
@Service
public class TalentBankCandidateService {

	static final int MAX_PAGE_SIZE = 50;
	static final int MAX_SEARCH_LENGTH = 120;
	static final int MAX_MATCHES = 500;

	private final TalentBankAccess access;
	private final TalentBankEnrollmentRepository enrollments;
	private final TalentBankEventRepository events;
	private final TalentCandidateReader candidates;
	private final UserProfileRepository profiles;
	private final ManageProfileExtensionUseCase portfolio;
	private final TalentBankResumeReader resumes;
	private final AuditTrail audit;
	private final Clock clock;

	public TalentBankCandidateService(TalentBankAccess access, TalentBankEnrollmentRepository enrollments,
			TalentBankEventRepository events, TalentCandidateReader candidates, UserProfileRepository profiles,
			ManageProfileExtensionUseCase portfolio, TalentBankResumeReader resumes, AuditTrail audit, Clock clock) {
		this.access = access;
		this.enrollments = enrollments;
		this.events = events;
		this.candidates = candidates;
		this.profiles = profiles;
		this.portfolio = portfolio;
		this.resumes = resumes;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * A lista de inscritos, com a busca e os filtros aplicados sobre as pessoas INSCRITAS.
	 *
	 * <p>A ordem é a dos consentimentos: o conjunto de ids vem das inscrições (e só delas), e a busca
	 * e os filtros escolhem dentro dele — nunca o contrário.</p>
	 */
	public CandidatePage page(String ownerId, String bankId, String cursor, String search, String state, String skill,
			Integer minExperiences, String education, int limit) {
		TalentBank bank = access.requireOwnedBank(ownerId, bankId);
		int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
		EnrollmentState wanted = parseState(state);
		String term = trim(search, MAX_SEARCH_LENGTH);

		List<String> searchable = wanted == null
				? enrollments.candidateIdsOfBank(bank.id())
				: enrollments.candidateIdsByState(bank.id(), wanted);
		if (StringUtils.hasText(term)) {
			searchable = candidates.searchAmong(searchable, term, MAX_MATCHES);
		}
		TalentCandidateFilter filter = new TalentCandidateFilter(trim(skill, 80), minExperiences,
			trim(education, 120));
		if (!filter.isEmpty()) {
			searchable = candidates.filterAmong(searchable, filter);
		}

		TalentBankCursor.Cursor decoded = TalentBankCursor.decode(cursor);
		List<TalentBankEnrollment> window = enrollments.pageOfBank(bank.id(), searchable, wanted, decoded.instant(),
			decoded.id(), size + 1);
		boolean hasMore = window.size() > size;
		List<TalentBankEnrollment> pageRows = hasMore ? window.subList(0, size) : window;

		Map<String, TalentCandidate> profilesById = new LinkedHashMap<>();
		List<String> visibleIds = pageRows.stream()
			.filter(TalentBankEnrollment::grantsAccess)
			.map(TalentBankEnrollment::candidateId)
			.toList();
		for (TalentCandidate candidate : candidates.byIds(visibleIds)) {
			profilesById.put(candidate.userId(), candidate);
		}

		List<CandidateRow> rows = new ArrayList<>();
		for (TalentBankEnrollment enrollment : pageRows) {
			TalentCandidate candidate = profilesById.get(enrollment.candidateId());
			rows.add(new CandidateRow(enrollment.id(), enrollment.state().name(), enrollment.candidateId(),
				candidate == null ? null : candidate.displayName(), candidate == null ? null : candidate.username(),
				candidate == null ? null : candidate.headline(), candidate == null ? null : candidate.avatar(),
				candidate == null ? null : candidate.location(), candidate == null ? 0 : candidate.experienceCount(),
				enrollment.sharedFields(), enrollment.note(), instant(enrollment.consentedAt()),
				candidate != null && StringUtils.hasText(candidate.resumeName()),
				candidate == null ? null : candidate.resumeName()));
		}
		TalentBankEnrollment last = pageRows.isEmpty() ? null : pageRows.get(pageRows.size() - 1);
		String nextCursor = hasMore && last != null ? TalentBankCursor.encode(last.updatedAt(), last.id()) : null;
		return new CandidatePage(rows, nextCursor, enrollments.countByBankAndState(bank.id(), EnrollmentState.ACTIVE),
			enrollments.countByBankAndState(bank.id(), EnrollmentState.PENDING));
	}

	/**
	 * A ficha de uma pessoa inscrita — só o que ela autorizou.
	 *
	 * <p>Cada bloco chega ou não conforme o consentimento: sem SKILLS, não há competências; sem
	 * RESUME, não há currículo. A ficha é montada na hora, a partir do perfil de hoje.</p>
	 */
	public CandidateDetail detail(String ownerId, String bankId, String enrollmentId) {
		TalentBank bank = access.requireOwnedBank(ownerId, bankId);
		TalentBankEnrollment enrollment = requireEnrollment(bank.id(), enrollmentId);
		if (!enrollment.grantsAccess()) {
			throw new ApiException(403, "ENROLLMENT_NOT_ACTIVE",
				"Esta inscrição não está ativa: não há dados autorizados para ver.");
		}
		String candidateId = enrollment.candidateId();
		UserProfile profile = profiles.findById(candidateId)
			.orElseThrow(() -> new ApiException(404, "CANDIDATE_NOT_FOUND", "Candidato não encontrado."));

		ProfileView identity = null;
		if (enrollment.shares(SharedField.PROFILE)) {
			identity = new ProfileView(profile.displayName(), profile.username(), profile.headline(), profile.avatar(),
				profile.location());
		}
		List<String> skills = enrollment.shares(SharedField.SKILLS) ? profile.skills() : List.of();
		List<ExperienceView> experiences = enrollment.shares(SharedField.EXPERIENCES)
				? portfolio.publicExperiences(candidateId)
				: List.of();
		List<EducationView> educations = enrollment.shares(SharedField.EDUCATION)
				? portfolio.getEducations(candidateId)
				: List.of();
		List<CertificationView> certifications = enrollment.shares(SharedField.CERTIFICATIONS)
				? portfolio.getCertifications(candidateId)
				: List.of();
		boolean resumeAuthorized = enrollment.shares(SharedField.RESUME);
		boolean resumeAvailable = resumeAuthorized && StringUtils.hasText(profile.resumeUrl());
		return new CandidateDetail(enrollment.id(), enrollment.state().name(), enrollment.sharedFields(),
			enrollment.consentVersion(), enrollment.purposeSnapshot(), enrollment.note(),
			instant(enrollment.consentedAt()), identity, skills, experiences, educations, certifications,
			resumeAuthorized, resumeAvailable, resumeAuthorized ? profile.resumeName() : null);
	}

	/** O encerramento pelo recrutador: o acesso morre para a pessoa, e a decisão fica registrada. */
	@Transactional
	public TalentBankEnrollmentView close(String ownerId, String bankId, String enrollmentId) {
		TalentBank bank = access.requireOwnedBank(ownerId, bankId);
		TalentBankEnrollment enrollment = requireEnrollment(bank.id(), enrollmentId);
		if (enrollment.state() == EnrollmentState.CLOSED_BY_RECRUITER) {
			return TalentBankEnrollmentView.of(enrollment);
		}
		if (enrollment.state() != EnrollmentState.ACTIVE) {
			throw new ApiException(409, "ENROLLMENT_NOT_ACTIVE", "Esta inscrição não está ativa.");
		}
		Instant now = clock.instant();
		TalentBankEnrollment saved = enrollments.save(enrollment.close(now));
		events.save(TalentBankEvent.of(saved.bankId(), saved.id(), ownerId, Action.CLOSED_BY_RECRUITER, null, now));
		audit.record(ownerId, "TALENT_BANK_ENROLLMENT_CLOSED", target(saved.bankId(), saved.id()),
			TalentBankService.json(Map.of("bankId", saved.bankId(), "candidateId", saved.candidateId())));
		return TalentBankEnrollmentView.of(saved);
	}

	/** A trilha da inscrição, vista pelo recrutador. */
	public List<TalentBankEventView> history(String ownerId, String bankId, String enrollmentId) {
		TalentBank bank = access.requireOwnedBank(ownerId, bankId);
		TalentBankEnrollment enrollment = requireEnrollment(bank.id(), enrollmentId);
		return events.findOfEnrollment(enrollment.id()).stream().map(TalentBankEventView::of).toList();
	}

	/**
	 * O currículo autorizado, servido pelo backend.
	 *
	 * <p>O endereço público do storage nunca sai daqui: a leitura é feita no servidor, depois de
	 * conferir o dono do arquivo, a inscrição ativa e o campo autorizado. E o acesso fica na trilha
	 * — quem viu o currículo de quem, e quando.</p>
	 */
	public ResumeFile resume(String ownerId, String bankId, String enrollmentId) {
		TalentBank bank = access.requireOwnedBank(ownerId, bankId);
		TalentBankEnrollment enrollment = requireEnrollment(bank.id(), enrollmentId);
		if (!enrollment.grantsAccess()) {
			throw new ApiException(403, "ENROLLMENT_NOT_ACTIVE",
				"Esta inscrição não está ativa: não há dados autorizados para ver.");
		}
		if (!enrollment.shares(SharedField.RESUME)) {
			throw new ApiException(403, "RESUME_NOT_AUTHORIZED",
				"O currículo não está entre as informações autorizadas por esta pessoa.");
		}
		UserProfile profile = profiles.findById(enrollment.candidateId())
			.orElseThrow(() -> new ApiException(404, "CANDIDATE_NOT_FOUND", "Candidato não encontrado."));
		ResumeFile file = resumes.read(enrollment.candidateId(), profile.resumeUrl(), profile.resumeName())
			.orElseThrow(() -> new ApiException(404, "RESUME_NOT_FOUND", "Currículo indisponível."));
		Instant now = clock.instant();
		events.save(TalentBankEvent.of(enrollment.bankId(), enrollment.id(), ownerId, Action.RESUME_VIEWED, null,
			now));
		audit.record(ownerId, "TALENT_BANK_RESUME_ACCESSED", target(enrollment.bankId(), enrollment.id()),
			TalentBankService.json(Map.of("bankId", enrollment.bankId(), "candidateId", enrollment.candidateId())));
		return file;
	}

	// ---------- Views ----------

	/** A linha da lista: quem é, o que autorizou e desde quando. */
	public record CandidateRow(String enrollmentId, String state, String userId, String name, String username,
			String headline, String avatar, String location, int experienceCount, List<String> sharedFields, String note,
			String consentedAt, boolean resumeAvailable, String resumeName) {
	}

	/** A ficha: só os blocos que a pessoa autorizou. */
	public record CandidateDetail(String enrollmentId, String state, List<String> sharedFields, String consentVersion,
			String purposeSnapshot, String note, String consentedAt, ProfileView identity, List<String> skills,
			List<ExperienceView> experiences, List<EducationView> educations, List<CertificationView> certifications,
			boolean resumeAuthorized, boolean resumeAvailable, String resumeName) {
	}

	/** A identificação profissional da pessoa. */
	public record ProfileView(String name, String username, String headline, String avatar, String location) {
	}

	public record CandidatePage(List<CandidateRow> candidates, String nextCursor, long activeTotal, long pendingTotal) {
	}

	// ---------- Regras ----------

	/** A inscrição tem que ser DESTA banca: a de outra é 404 — a mesma de quem não existe. */
	private TalentBankEnrollment requireEnrollment(String bankId, String enrollmentId) {
		return enrollments.findById(enrollmentId)
			.filter(enrollment -> enrollment.bankId().equals(bankId))
			.orElseThrow(() -> new ApiException(404, "ENROLLMENT_NOT_FOUND", "Inscrição não encontrada."));
	}

	/** O estado pedido no filtro; "ALL" (ou nada) devolve todos. */
	private EnrollmentState parseState(String raw) {
		if (!StringUtils.hasText(raw)) {
			return EnrollmentState.ACTIVE;
		}
		String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
		if ("ALL".equals(value)) {
			return null;
		}
		for (EnrollmentState state : EnrollmentState.values()) {
			if (state.name().equals(value)) {
				return state;
			}
		}
		throw new ApiException(400, "INVALID_ENROLLMENT_STATE", "Estado de inscrição desconhecido.");
	}

	private static String trim(String value, int max) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String text = value.trim();
		return text.length() > max ? text.substring(0, max) : text;
	}

	private static String instant(Instant value) {
		return value == null ? null : value.toString();
	}

	private static String target(String bankId, String enrollmentId) {
		return "talent_bank:" + bankId + ":" + enrollmentId;
	}
}

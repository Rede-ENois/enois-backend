package com.socialcoisa.rede.jobs.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.companies.application.CompanyService;
import com.socialcoisa.rede.companies.domain.Company;
import com.socialcoisa.rede.companies.domain.CompanyRole;
import com.socialcoisa.rede.jobs.application.port.out.JobAbandonPenaltyRepository;
import com.socialcoisa.rede.jobs.application.port.out.JobApplicationDelivery;
import com.socialcoisa.rede.jobs.application.port.out.JobApplicationStatusNotifier;
import com.socialcoisa.rede.jobs.application.port.out.JobRepository;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.jobs.domain.JobApplicationField;
import com.socialcoisa.rede.profile.application.ReputationService;
import com.socialcoisa.rede.shared.application.CacheConfig;
import com.socialcoisa.rede.shared.application.CacheInvalidator;
import com.socialcoisa.rede.shared.security.BehavioralTimingGuard;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class JobService {

	public static final int MIN_REPUTATION_TO_CREATE_JOB = 150;

	/** Teto de campos personalizados: um formulario de vaga nao precisa de mais do que isso. */
	public static final int MAX_APPLICATION_FIELDS = 25;

	/** Moedas aceitas na faixa salarial. BRL e o padrao. */
	public static final java.util.Set<String> SUPPORTED_CURRENCIES = java.util.Set.of("BRL", "USD", "EUR");

	/** Períodos aceitos no valor da vaga nova. Mês é o padrão. */
	public static final java.util.Set<String> SUPPORTED_SALARY_PERIODS = java.util.Set
		.of("HOUR", "DAY", "MONTH", "YEAR");

	private static final int MAX_SALARY_LENGTH = 120;

	/** Teto da descrição da vaga: o suficiente para explicá-la, sem virar página. */
	private static final int MAX_DESCRIPTION_LENGTH = 5000;

	/** Simbolos que o autor costuma digitar e que a exibicao remonta a partir da moeda. */
	private static final java.util.regex.Pattern CURRENCY_SYMBOLS = java.util.regex.Pattern
		.compile("(?i)(R\\$|US\\$|\\$|€)");

	private final JobRepository repository;
	private final ReputationService reputationService;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository;
	private final com.socialcoisa.rede.corporate.application.CorporateProfileService corporateProfiles;
	private final com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository extensionRepository;
	private final JobApplicationDelivery delivery;
	private final JobApplicationStatusNotifier statusNotifier;
	private final JobAbandonPenaltyRepository abandonPenalties;
	private final tools.jackson.databind.ObjectMapper objectMapper;
	private final Clock clock;
	private final UserRateLimiter userRateLimiter;
	private final BehavioralTimingGuard behavioralTimingGuard;
	private final CompanyService companyService;
	private final CacheInvalidator cacheInvalidator;

	public JobService(JobRepository repository, ReputationService reputationService,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository,
			com.socialcoisa.rede.corporate.application.CorporateProfileService corporateProfiles,
			com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository extensionRepository,
			JobApplicationDelivery delivery, JobApplicationStatusNotifier statusNotifier,
			JobAbandonPenaltyRepository abandonPenalties,
			tools.jackson.databind.ObjectMapper objectMapper, Clock clock,
			UserRateLimiter userRateLimiter, BehavioralTimingGuard behavioralTimingGuard,
			CompanyService companyService, CacheInvalidator cacheInvalidator) {
		this.repository = repository;
		this.reputationService = reputationService;
		this.userProfileRepository = userProfileRepository;
		this.corporateProfiles = corporateProfiles;
		this.extensionRepository = extensionRepository;
		this.delivery = delivery;
		this.statusNotifier = statusNotifier;
		this.abandonPenalties = abandonPenalties;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.userRateLimiter = userRateLimiter;
		this.behavioralTimingGuard = behavioralTimingGuard;
		this.companyService = companyService;
		this.cacheInvalidator = cacheInvalidator;
	}

	@Transactional
	public Job create(String title, String company, String logo, String location, String type, String level,
			String salary, String currency, String salaryMin, String salaryMax, String salaryPeriod,
			String description, List<String> skills, List<JobFieldInput> fields, String userId,
			boolean communityAgreement, String companyId) {
		// Posting for a company page takes RECRUITER or above on it; the job then carries the
		// page's name and logo, so nobody publishes in a page's name without being on its team.
		Company page = null;
		if (StringUtils.hasText(companyId)) {
			page = companyService.company(companyId.trim());
			companyService.requireRole(page, userId, CompanyRole.RECRUITER);
			company = page.name();
			logo = StringUtils.hasText(page.logo()) ? page.logo() : logo;
		}
		validate(title, company, type, level);
		int reputation = reputationService.reputationOf(userId);
		// Freelance e trabalho pontual: qualquer pessoa com conta verificada publica, sem o
		// gate de reputacao das vagas de empresa. Os demais guardas (sessao verificada, idade,
		// declaracao de regras, limites de escrita) continuam iguais aos das vagas.
		if ("FREELANCE".equalsIgnoreCase(type == null ? "" : type.trim())) {
			throw new ApiException(400, "USE_FREELANCE_ENDPOINT",
				"Trabalho pontual agora é publicado em /freelances, com escopo fechado, prazo e faixa de valor.");
		}
		if (reputation <= MIN_REPUTATION_TO_CREATE_JOB) {
			throw new ApiException(403, "LOW_REPUTATION", "E necessario ter mais de " + MIN_REPUTATION_TO_CREATE_JOB
				+ " de reputacao para criar vagas.");
		}
		// Publicar e afirmar que a vaga e real e respeita as regras da comunidade; guardamos o instante.
		if (!communityAgreement) {
			throw new ApiException(400, "COMMUNITY_RULES_NOT_ACCEPTED",
				"E preciso declarar que a vaga e real e esta de acordo com as regras da comunidade.");
		}
		// Valor e descrição são OBRIGATÓRIOS na vaga nova (item 5 do backlog): sem eles a vaga
		// não salva — ninguém perde tempo com uma vaga que não diz o que é nem quanto paga.
		SalaryRange range = validateSalary(description, salaryMin, salaryMax, salaryPeriod);
		String id = "job_" + UUID.randomUUID().toString().replace("-", "");
		Job job = Job.create(id, title, company, logo, location, type, level, normalizeSalary(salary),
			normalizeCurrency(currency), range.min(), range.max(), range.period(), range.description(), skills,
			normalizeFields(id, fields), userId, clock.instant(), clock.instant());
		if (page != null) {
			job = job.withCompany(page.id());
		}
		repository.save(job);
		cacheInvalidator.afterCommit(CacheConfig.JOBS);
		if (page != null) {
			companyService.notifyFollowers(page, userId, "Nova vaga", page.name() + " abriu a vaga \"" + job.title() + "\".");
		}
		return job;
	}

	@Transactional
	public Job update(String jobId, String title, String company, String logo, String location, String type, String level,
			String salary, String currency, String salaryMin, String salaryMax, String salaryPeriod,
			String description, List<String> skills, List<JobFieldInput> fields, String userId) {
		validate(title, company, type, level);
		Job existing = repository.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		if (!existing.postedBy().equals(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Voce nao pode editar uma vaga que nao criou.");
		}
		// A job linked to a page keeps the page's name and logo: editing does not move it out.
		if (existing.companyId() != null) {
			company = existing.company();
			logo = existing.logo();
		}
		// A edição segue a mesma regra da criação: valor e descrição obrigatórios.
		SalaryRange range = validateSalary(description, salaryMin, salaryMax, salaryPeriod);
		// Editar NAO mexe no ciclo de vida: status e closedAt vem do registro atual, nunca do
		// corpo da requisicao — senao bastava mandar status=OPEN para reabrir uma vaga encerrada.
		Job updated = Job.restore(jobId, title, company, logo, location, type, level, normalizeSalary(salary),
			normalizeCurrency(currency), range.min(), range.max(), range.period(), range.description(), skills,
			normalizeFields(jobId, fields), userId, existing.communityAgreedAt(),
			existing.status(), existing.closedAt(), existing.postedAt(), clock.instant(), existing.companyId());
		repository.save(updated);
		cacheInvalidator.afterCommit(CacheConfig.JOBS);
		return updated;
	}

	/**
	 * Encerra o processo seletivo. Encerrar preserva tudo: as candidaturas ja recebidas continuam
	 * disponiveis para a triagem — quem exclui e o `delete`, que e outra operacao.
	 */
	@Transactional
	public Job close(String jobId, String userId) {
		// Mesmo lock do apply: encerrar e candidatar-se disputam o mesmo ponto de serializacao.
		Job existing = repository.findByIdForUpdate(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		if (!existing.postedBy().equals(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Voce nao pode encerrar uma vaga que nao criou.");
		}
		// Repetir o encerramento e erro de estado, nao sucesso silencioso: o autor precisa saber
		// que a acao nao mudou nada agora.
		if (!existing.isOpen()) {
			throw new ApiException(409, "JOB_ALREADY_CLOSED", "Esta vaga ja esta encerrada.");
		}
		Job closed = existing.closed(clock.instant());
		repository.save(closed);
		cacheInvalidator.afterCommit(CacheConfig.JOBS);
		return closed;
	}

	/**
	 * Move a candidatura de estado. So o dono da vaga; a transicao e validada no dominio
	 * ({@link JobApplication#withStatus}), entao nao existe caminho que grave estado arbitrario.
	 *
	 * O lock da vaga e o mesmo do encerramento: duas triagens concorrentes na mesma vaga
	 * serializam, e uma triagem nao passa por fora de um encerramento em andamento.
	 */
	/** Candidaturas que a pessoa fez, com o estado e o retorno do empregador. */
	public List<JobApplication> myApplications(String userId, int limit) {
		return repository.findApplicationsByUser(userId, limit);
	}

	@Transactional
	public JobApplication updateApplicationStatus(String jobId, String applicationId, String userId,
			JobApplicationStatus newStatus, String feedback) {
		Job job = repository.findByIdForUpdate(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		if (!job.postedBy().equals(userId)) {
			throw new ApiException(403, "NOT_OWNER", "So quem publicou a vaga pode gerenciar as candidaturas.");
		}
		// A candidatura tem que ser DESTA vaga: sem isso, um id de outra vaga seria alcancavel
		// pelo caminho da URL.
		JobApplication application = repository.findApplicationById(applicationId)
			.filter(found -> found.jobId().equals(jobId))
			.orElseThrow(() -> new ApiException(404, "APPLICATION_NOT_FOUND", "Candidatura nao encontrada."));

		// O dominio valida a transicao E o motivo (reprovar exige retorno): nenhum caminho novo
		// consegue gravar um "nao selecionado" mudo.
		JobApplication moved = application.withStatus(newStatus, feedback, clock.instant());
		repository.updateApplicationStatus(applicationId, moved.status(), moved.feedback(), moved.reviewedAt());

		// So depois de persistir: o estado e a fonte da verdade, a notificacao vem dele.
		statusNotifier.notifyStatusChange(job, moved, moved.status());
		return moved;
	}

	/**
	 * Exclui a vaga. Candidaturas ainda em APPLIED (o empregador nunca deu retorno) geram
	 * REGISTRO de penalidade no componente de integridade da credibilidade — uma linha por
	 * vaga, com teto no cálculo. Quem respondeu todas (IN_REVIEW/ADVANCED/NOT_SELECTED) não
	 * é penalizado: a regra é "sem resposta reduz credibilidade; com resposta, não".
	 */
	public record DeleteResult(String jobId, int unanswered, boolean penalized) {
	}

	@Transactional
	public DeleteResult delete(String jobId, String userId) {
		Job existing = repository.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		if (!existing.postedBy().equals(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Voce nao pode excluir uma vaga que nao criou.");
		}
		int unanswered = repository.countUnansweredApplications(jobId);
		boolean penalized = unanswered > 0 && abandonPenalties.record(jobId, userId, unanswered);
		repository.deleteById(jobId);
		cacheInvalidator.afterCommit(CacheConfig.JOBS);
		return new DeleteResult(jobId, unanswered, penalized);
	}

	/**
	 * Candidatura: valida os campos que o autor definiu, congela o retrato do perfil do candidato e
	 * entrega a candidatura ao empregador (e-mail + mensagem direta).
	 */
	@Transactional
	public JobApplication apply(String jobId, String userId, String message, List<JobAnswerInput> answers) {
		userRateLimiter.require(userId, UserRateLimiter.APPLY_JOB);
		behavioralTimingGuard.record(userId);
		// Trava a linha da vaga: a checagem de "esta aberta" e a gravacao da candidatura acontecem
		// na mesma transacao, entao um encerramento concorrente espera em vez de passar por fora.
		Job job = repository.findByIdForUpdate(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		// Encerrada nao aceita candidatura — e o bloqueio e aqui, antes de gravar e antes de
		// entregar ao empregador: esconder o botao no app nao impediria a chamada direta.
		if (!job.isOpen()) {
			throw new ApiException(409, "JOB_CLOSED", "Esta vaga foi encerrada e nao recebe mais candidaturas.");
		}
		if (job.postedBy().equals(userId)) {
			throw new ApiException(400, "CANNOT_APPLY_TO_OWN_JOB", "Voce nao pode se candidatar a sua propria vaga.");
		}
		if (repository.hasApplied(jobId, userId)) {
			throw new ApiException(409, "ALREADY_APPLIED", "Voce ja se candidatou a esta vaga.");
		}
		// A candidatura e SEMPRE da identidade profissional ativa, resolvida pela sessao aqui no
		// servidor: sem ela nao ha candidatura (o app oferece criar ou ativar o perfil).
		var professional = corporateProfiles.requireActive(userId);
		List<JobApplication.JobApplicationAnswer> normalized = validateAnswers(job, answers);
		String snapshot = snapshotOf(userId, professional);
		JobApplication application = JobApplication.create(null, jobId, userId, trimToNull(message, 2000), snapshot,
			clock.instant(), normalized);
		String applicationId = repository.saveApplication(application);
		// Candidatura nova nasce sempre APPLIED: o estado nao vem de fora.
		JobApplication saved = JobApplication.restore(applicationId, jobId, userId, application.message(), snapshot,
			application.appliedAt(), normalized, JobApplicationStatus.APPLIED, null, null);

		// Entrega: o empregador recebe por e-mail e na mensagem direta, com o perfil do candidato.
		delivery.deliver(job, saved);
		return saved;
	}

	/** Candidaturas recebidas por uma vaga: somente o autor da vaga ve quem se candidatou. */
	public List<JobApplication> applicationsOf(String jobId, String requesterId, int limit) {
		Job job = repository.findById(jobId)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
		if (!job.postedBy().equals(requesterId)) {
			throw new ApiException(403, "NOT_OWNER", "So quem publicou a vaga ve as candidaturas recebidas.");
		}
		return repository.findApplicationsByJob(jobId, Math.min(Math.max(limit, 1), 100));
	}

	/**
	 * Valida as respostas contra os campos da vaga: obrigatorio sem resposta barra a candidatura, e o
	 * valor precisa combinar com o tipo do campo (numero, link).
	 */
	private List<JobApplication.JobApplicationAnswer> validateAnswers(Job job, List<JobAnswerInput> answers) {
		Map<String, String> byField = new java.util.LinkedHashMap<>();
		if (answers != null) {
			for (JobAnswerInput answer : answers) {
				if (answer != null && StringUtils.hasText(answer.fieldId())) {
					byField.put(answer.fieldId().trim(), answer.value() == null ? "" : answer.value().trim());
				}
			}
		}

		List<String> missing = new java.util.ArrayList<>();
		List<JobApplication.JobApplicationAnswer> result = new java.util.ArrayList<>();
		for (JobApplicationField field : job.applicationFields()) {
			String value = byField.get(field.id());
			if (!StringUtils.hasText(value)) {
				if (field.required()) {
					missing.add(field.label());
				}
				continue;
			}
			if (value.length() > 2000) {
				throw new ApiException(400, "ANSWER_TOO_LONG",
					"A resposta de \"" + field.label() + "\" passa de 2000 caracteres.");
			}
			if (field.type() == JobApplicationField.FieldType.NUMBER && !value.matches("[-+]?\\d+([.,]\\d+)?")) {
				throw new ApiException(400, "INVALID_ANSWER",
					"O campo \"" + field.label() + "\" espera um numero.");
			}
			if (field.type() == JobApplicationField.FieldType.LINK
					&& !value.matches("(?i)^(https?://\\S+\\.\\S+|\\S+\\.\\S{2,}/?\\S*)$")) {
				throw new ApiException(400, "INVALID_ANSWER",
					"O campo \"" + field.label() + "\" espera um link.");
			}
			result.add(new JobApplication.JobApplicationAnswer(field.id(), field.label(), value, field.position()));
		}

		if (!missing.isEmpty()) {
			throw new ApiException(400, "MISSING_REQUIRED_ANSWERS",
				"Responda os campos obrigatorios: " + String.join(", ", missing) + ".");
		}
		return result;
	}

	/**
	 * Retrato do candidato no momento da inscricao: perfil, tempo de experiencia e credibilidade.
	 *
	 * A apresentacao (nome, @, headline, local, avatar e industria) e a da IDENTIDADE PROFISSIONAL,
	 * nunca a social: a empresa nao recebe o @ nem a foto do perfil pessoal. O resto sao valores da
	 * conta, iguais para as duas identidades (reputacao, experiencia, habilidades, curriculo,
	 * verificacao). O nome social continua: e direito da pessoa, nao dado do perfil social.
	 */
	private String snapshotOf(String userId, com.socialcoisa.rede.corporate.domain.CorporateProfile professional) {
		var profile = userProfileRepository.findById(userId).orElse(null);
		int reputation = reputationService.reputationOf(userId);
		int years = yearsOfExperience(userId);
		Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
		snapshot.put("identity", "CORPORATE");
		snapshot.put("name", professional.name());
		snapshot.put("socialName", profile == null || profile.socialName() == null ? "" : profile.socialName());
		snapshot.put("username", professional.username());
		snapshot.put("headline", textOrEmpty(professional.headline()));
		snapshot.put("location", textOrEmpty(professional.location()));
		snapshot.put("avatar", textOrEmpty(professional.avatar()));
		snapshot.put("industry", textOrEmpty(professional.industry()));
		snapshot.put("skills", profile == null ? List.of() : profile.skills());
		snapshot.put("experienceYears", years);
		snapshot.put("experiencesCount", extensionRepository.countExperiencesByUserId(userId));
		snapshot.put("reputation", reputation);
		snapshot.put("identityVerified", profile != null && profile.identityVerified());
		// Curriculo do momento da candidatura: entra no retrato, nao numa coluna nova — se a pessoa
		// trocar o PDF depois, a candidatura antiga continua mostrando o que foi enviado.
		snapshot.put("resumeUrl", profile == null || profile.resumeUrl() == null ? "" : profile.resumeUrl());
		snapshot.put("resumeName", profile == null || profile.resumeName() == null ? "" : profile.resumeName());
		try {
			return objectMapper.writeValueAsString(snapshot);
		}
		catch (Exception exception) {
			throw new ApiException(500, "SNAPSHOT_FAILURE", "Nao foi possivel montar o seu perfil para o envio.");
		}
	}

	private static String textOrEmpty(String value) {
		return StringUtils.hasText(value) ? value : "";
	}

	/** Anos de experiencia a partir da primeira experiencia registrada (0 quando nao ha nenhuma). */
	private int yearsOfExperience(String userId) {
		java.time.LocalDate earliest = extensionRepository.findExperiencesByUserId(userId).stream()
			.map(experience -> experience.startDate())
			.filter(java.util.Objects::nonNull)
			.min(java.time.LocalDate::compareTo)
			.orElse(null);
		if (earliest == null) {
			return 0;
		}
		return Math.max(0, java.time.Period.between(earliest, java.time.LocalDate.now()).getYears());
	}

	/** Campos enviados pelo autor: geram id proprio e entram na ordem em que foram criados. */
	private List<JobApplicationField> normalizeFields(String jobId, List<JobFieldInput> fields) {
		if (fields == null || fields.isEmpty()) {
			return List.of();
		}
		if (fields.size() > MAX_APPLICATION_FIELDS) {
			throw new ApiException(400, "TOO_MANY_FIELDS",
				"O formulario aceita no maximo " + MAX_APPLICATION_FIELDS + " campos.");
		}
		List<JobApplicationField> normalized = new java.util.ArrayList<>();
		Set<String> labels = new java.util.LinkedHashSet<>();
		for (int index = 0; index < fields.size(); index++) {
			JobFieldInput input = fields.get(index);
			if (input == null || !StringUtils.hasText(input.label())) {
				continue;
			}
			String label = input.label().trim();
			if (label.length() > 160) {
				throw new ApiException(400, "INVALID_FIELD_LABEL", "O campo deve ter no maximo 160 caracteres.");
			}
			if (!labels.add(label.toLowerCase(java.util.Locale.ROOT))) {
				throw new ApiException(400, "DUPLICATE_FIELD_LABEL", "Ha dois campos com o mesmo nome: " + label + ".");
			}
			normalized.add(JobApplicationField.create(
				"field_" + UUID.randomUUID().toString().replace("-", ""), jobId, label,
				JobApplicationField.FieldType.from(input.type()), input.required(), index));
		}
		return normalized;
	}

	private String trimToNull(String value, int max) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
	}

	/** Campos que o autor define no formulario de candidatura. */
	public record JobFieldInput(String label, String type, boolean required) {
	}

	/** Resposta do candidato a um campo da vaga. */
	public record JobAnswerInput(String fieldId, String value) {
	}

	public java.util.List<String> savedJobIds(String userId) {
		return repository.findSavedJobIds(userId);
	}

	public java.util.List<Job> findByIds(java.util.Collection<String> ids) {
		return repository.findByIds(ids);
	}

	@Transactional
	public void save(String jobId, String userId) {
		if (repository.hasBookmarked(jobId, userId)) {
			throw new ApiException(409, "ALREADY_SAVED", "Vaga ja salva.");
		}
		repository.saveBookmark(jobId, userId);
	}

	@Transactional
	public void removeSave(String jobId, String userId) {
		if (!repository.hasBookmarked(jobId, userId)) {
			throw new ApiException(404, "NOT_SAVED", "Vaga nao estava salva.");
		}
		repository.removeBookmark(jobId, userId);
	}

	/**
	 * Vitrine de vagas. O resultado e o mesmo para todo mundo — a parte pessoal (ja me candidatei,
	 * salvei, sou dono) e enriquecida depois, no controller — por isso pode ficar em cache.
	 */
	@Cacheable(cacheNames = CacheConfig.JOBS,
		key = "#query + '|' + #type + '|' + #level + '|' + #location + '|' + #skills + '|' + #before + '|' + #limit",
		sync = true)
	public List<Job> list(String query, String type, String level, String location, List<String> skills,
			java.time.Instant before, int limit) {
		return repository.findAll(query, type, level, location, skills, before, limit);
	}

	public Job getById(String id) {
		return repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "JOB_NOT_FOUND", "Vaga nao encontrada."));
	}

	/** Vagas publicadas pelo usuario (com quantas candidaturas receberam). */
	public List<Job> listMine(String userId, java.time.Instant before, int limit) {
		return repository.findByPostedBy(userId, before, limit);
	}

	/** Vagas em que o usuario se candidatou. */
	public List<Job> listApplications(String userId, java.time.Instant before, int limit) {
		return repository.findAppliedByUser(userId, before, limit);
	}

	public java.util.Set<String> appliedJobIds(String userId, java.util.List<String> jobIds) {
		return repository.findAppliedJobIds(userId, jobIds);
	}

	public java.util.Set<String> savedJobIds(String userId, java.util.List<String> jobIds) {
		return repository.findSavedJobIds(userId, jobIds);
	}

	public java.util.Map<String, Integer> applicationsCountByJobIds(java.util.List<String> jobIds) {
		return repository.countApplicationsByJobIds(jobIds);
	}

	public int applicationsCount(String jobId) {
		return repository.countApplications(jobId);
	}

	private void validate(String title, String company, String type, String level) {
		if (!StringUtils.hasText(title)) {
			throw new ApiException(400, "INVALID_JOB", "Titulo da vaga e obrigatorio.");
		}
		if (title.trim().length() > 255) {
			throw new ApiException(400, "INVALID_JOB", "Titulo da vaga deve ter no maximo 255 caracteres.");
		}
		if (!StringUtils.hasText(company)) {
			throw new ApiException(400, "INVALID_JOB", "Empresa e obrigatoria.");
		}
		if (company.trim().length() > 255) {
			throw new ApiException(400, "INVALID_JOB", "Empresa deve ter no maximo 255 caracteres.");
		}
		if (!StringUtils.hasText(type)) {
			throw new ApiException(400, "INVALID_JOB", "Tipo de contratacao e obrigatorio.");
		}
		if (!StringUtils.hasText(level)) {
			throw new ApiException(400, "INVALID_JOB", "Nivel da vaga e obrigatorio.");
		}
	}

	/** Valor estruturado da vaga nova: descrição obrigatória, mínimo > 0, faixa coerente. */
	private SalaryRange validateSalary(String description, String salaryMin, String salaryMax,
			String salaryPeriod) {
		if (!StringUtils.hasText(description)) {
			throw new ApiException(400, "DESCRIPTION_REQUIRED",
				"Descreva a vaga: o que a pessoa vai fazer e como será o trabalho.");
		}
		if (description.trim().length() > MAX_DESCRIPTION_LENGTH) {
			throw new ApiException(400, "DESCRIPTION_TOO_LONG",
				"A descrição da vaga deve ter no máximo " + MAX_DESCRIPTION_LENGTH + " caracteres.");
		}
		BigDecimal min = parseAmount(salaryMin);
		if (min == null || min.signum() <= 0) {
			throw new ApiException(400, "SALARY_REQUIRED",
				"Declare o valor da vaga: um número maior que zero.");
		}
		BigDecimal max = parseAmount(salaryMax);
		if (max != null && max.compareTo(min) < 0) {
			throw new ApiException(400, "INVALID_SALARY_RANGE",
				"O teto da faixa não pode ser menor que o valor mínimo.");
		}
		String period = StringUtils.hasText(salaryPeriod) ? salaryPeriod.trim().toUpperCase(java.util.Locale.ROOT)
			: "MONTH";
		if (!SUPPORTED_SALARY_PERIODS.contains(period)) {
			throw new ApiException(400, "INVALID_SALARY_PERIOD",
				"O período do valor deve ser hora, dia, mês ou ano.");
		}
		return new SalaryRange(min, max, period, description.trim());
	}

	/**
	 * Converte o que o autor digitou em número, aceitando os formatos brasileiros: "1500",
	 * "1.500", "1.500,50" e "R$ 1.500". Ponto sozinho com 3 dígitos após é separador de milhar,
	 * não decimal — "1.500" é mil e quinhentos, não um e meio.
	 */
	private static BigDecimal parseAmount(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String cleaned = value.replaceAll("[^0-9.,]", "");
		if (cleaned.isEmpty()) {
			return null;
		}
		if (cleaned.contains(",") && cleaned.contains(".")) {
			cleaned = cleaned.replace(".", "").replace(',', '.');
		} else if (cleaned.contains(",")) {
			cleaned = cleaned.replace(',', '.');
		} else if (cleaned.matches("\\d{1,3}(\\.\\d{3})+")) {
			cleaned = cleaned.replace(".", "");
		}
		try {
			return new BigDecimal(cleaned);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	record SalaryRange(BigDecimal min, BigDecimal max, String period, String description) {
	}

	/**
	 * Deixa o valor da faixa salarial sem simbolo de moeda: o simbolo e escolhido no campo `currency`
	 * e remontado na exibicao, entao a mesma vaga nunca aparece como "R$ R$ 8.000" nem com dois
	 * simbolos diferentes.
	 */
	private static String normalizeSalary(String salary) {
		if (!StringUtils.hasText(salary)) {
			return null;
		}
		String cleaned = CURRENCY_SYMBOLS.matcher(salary).replaceAll(" ").replaceAll("\\s+", " ").trim();
		if (cleaned.isEmpty()) {
			return null;
		}
		return cleaned.length() > MAX_SALARY_LENGTH ? cleaned.substring(0, MAX_SALARY_LENGTH) : cleaned;
	}

	/** Moeda vigente da vaga: aceita apenas os codigos suportados; vazio vira BRL. */
	private static String normalizeCurrency(String currency) {
		if (!StringUtils.hasText(currency)) {
			return "BRL";
		}
		String code = currency.trim().toUpperCase(java.util.Locale.ROOT);
		if (!SUPPORTED_CURRENCIES.contains(code)) {
			throw new ApiException(400, "INVALID_JOB_CURRENCY",
				"Moeda nao suportada. Use BRL, USD ou EUR.");
		}
		return code;
	}

}

package com.socialcoisa.rede.jobs.adapters.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.jobs.application.port.out.CompanyDirectoryReader;
import com.socialcoisa.rede.jobs.application.port.out.ExternalJobRepository;
import com.socialcoisa.rede.jobs.domain.ExternalJob;
import com.socialcoisa.rede.jobs.application.JobInterviewService;
import com.socialcoisa.rede.jobs.application.JobService;
import com.socialcoisa.rede.jobs.domain.Job;
import com.socialcoisa.rede.jobs.domain.JobInterview;
import com.socialcoisa.rede.jobs.domain.JobApplicationStatus;
import com.socialcoisa.rede.jobs.domain.JobApplication;
import com.socialcoisa.rede.profile.application.NameStyleView;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.CursorCodec;
import com.socialcoisa.rede.shared.web.PageResponse;
import com.socialcoisa.rede.shared.web.StatusResponse;

@RestController
@RequestMapping("/jobs")
class JobContractController {

	private final IdempotencyApplicationService idempotency;
	private final CurrentUserResolver currentUserResolver;
	private final JobService jobService;
	private final CursorCodec cursorCodec;
	private final com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository;
	private final com.socialcoisa.rede.profile.application.ReputationService reputationService;
	private final List<com.socialcoisa.rede.jobs.application.port.out.JobImportSource> jobImportSources;
	private final ExternalJobRepository externalJobRepository;
	private final com.socialcoisa.rede.jobs.application.port.out.CompanyDirectoryReader companyDirectory;
	private final com.socialcoisa.rede.jobs.application.ExternalJobSubmissionService externalJobSubmission;
	private final com.socialcoisa.rede.jobs.application.ExternalJobApplicationService externalJobApplication;
	private final boolean externalListingEnabled;
	private final int externalListingLimit;
	private final tools.jackson.databind.ObjectMapper objectMapper;
	private final com.socialcoisa.rede.companies.application.CompanyService companyService;
	private final JobInterviewService interviewService;

	JobContractController(IdempotencyApplicationService idempotency, CurrentUserResolver currentUserResolver,
			JobService jobService, CursorCodec cursorCodec,
			com.socialcoisa.rede.profile.application.port.out.UserProfileRepository userProfileRepository,
			com.socialcoisa.rede.profile.application.ReputationService reputationService,
			tools.jackson.databind.ObjectMapper objectMapper,
			List<com.socialcoisa.rede.jobs.application.port.out.JobImportSource> jobImportSources,
			ExternalJobRepository externalJobRepository,
			@org.springframework.beans.factory.annotation.Value("${rede.jobs.import.external-listing-enabled:false}") boolean externalListingEnabled,
			@org.springframework.beans.factory.annotation.Value("${rede.jobs.import.external-listing-limit:20}") int externalListingLimit,
			com.socialcoisa.rede.jobs.application.port.out.CompanyDirectoryReader companyDirectory,
			com.socialcoisa.rede.jobs.application.ExternalJobSubmissionService externalJobSubmission,
			com.socialcoisa.rede.jobs.application.ExternalJobApplicationService externalJobApplication,
			com.socialcoisa.rede.companies.application.CompanyService companyService,
			JobInterviewService interviewService) {
		this.companyService = companyService;
		this.interviewService = interviewService;
		this.idempotency = idempotency;
		this.currentUserResolver = currentUserResolver;
		this.jobService = jobService;
		this.cursorCodec = cursorCodec;
		this.userProfileRepository = userProfileRepository;
		this.reputationService = reputationService;
		this.objectMapper = objectMapper;
		this.companyDirectory = companyDirectory;
		this.jobImportSources = jobImportSources;
		this.externalJobRepository = externalJobRepository;
		this.externalListingEnabled = externalListingEnabled;
		this.externalListingLimit = externalListingLimit;
		this.externalJobSubmission = externalJobSubmission;
		this.externalJobApplication = externalJobApplication;
	}

	/** Candidaturas recebidas (somente quem publicou a vaga). */
	@GetMapping("/{jobId}/applications")
	List<ApplicationView> applications(@PathVariable String jobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		java.util.Map<String, JobInterview> interviews = interviewService.scheduledByApplication(jobId, user.id().value());
		return jobService.applicationsOf(jobId, user.id().value(), limit).stream()
			.map(application -> new ApplicationView(application.id(), application.jobId(), application.status().name(),
				application.appliedAt().toString(), application.answers().size(),
				toCandidateView(application), application.message(),
				application.answers().stream()
					.map(answer -> new AnswerView(answer.label(), answer.value()))
					.toList(),
				application.feedback(),
				application.reviewedAt() != null ? application.reviewedAt().toString() : null,
				interviews.containsKey(application.id()) ? InterviewView.of(interviews.get(application.id())) : null))
			.toList();
	}

	@PostMapping
	ResponseEntity<JobView> create(@RequestBody CreateJobRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Job job = jobService.create(body.title(), body.company(), body.logo(), body.location(), body.type(), body.level(),
			body.salary(), body.currency(), body.salaryMin(), body.salaryMax(), body.salaryPeriod(),
			body.description(), body.skills(), toFieldInputs(body.applicationFields()), user.id().value(),
			body.communityAgreement(), body.companyId());
		return ResponseEntity.status(HttpStatus.CREATED).body(toView(job, user.id().value(), false, false, 0));
	}

	@GetMapping
	PageResponse<JobView> jobs(@RequestParam(required = false) String query,
			@RequestParam(required = false) String type, @RequestParam(required = false) String level,
			@RequestParam(required = false) String location,
			@RequestParam(required = false) List<String> skills,
			@RequestParam(required = false) String source, @RequestParam(required = false) String platform,
			@RequestParam(required = false) String workModel,
			@RequestParam(required = false) String employmentType,
			@RequestParam(required = false) String city, @RequestParam(required = false) String state,
			@RequestParam(required = false) String country,
			@RequestParam(required = false) java.math.BigDecimal salaryMin,
			@RequestParam(required = false) Boolean remoteOnly,
			@RequestParam(required = false) String publishedAfter,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		limit = pageLimit(limit);
		// As ABAS: EXTERNAL devolve SO as externas (com filtro de agregador); INTERNAL nunca
		// mistura. Sem `source` mantem o comportamento antigo (internas + externas na 1a pagina).
		boolean soExternas = "EXTERNAL".equalsIgnoreCase(source);
		boolean soInternas = "INTERNAL".equalsIgnoreCase(source);
		String viewer = viewerId(sessionToken);
		if (soExternas) {
			return externalPage(query, platform, level, workModel, employmentType, city, state, country,
				salaryMin, remoteOnly, publishedAfter, sort, cursor, limit, viewer != null);
		}
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Job> jobs = jobService.list(query, type, level, location, skills, before, limit);
		// Externas nao tem tipo/nivel/local/skills: se o usuario filtrou por isso, elas nao entram.
		boolean structuralFilters = org.springframework.util.StringUtils.hasText(type)
			|| org.springframework.util.StringUtils.hasText(level)
			|| org.springframework.util.StringUtils.hasText(location)
			|| (skills != null && !skills.isEmpty());
		boolean firstPage = cursor == null;
		return page(jobs, limit, viewer, PageKind.LIST,
			!soInternas && firstPage && !structuralFilters, query);
	}

	/** As FAVORITAS: ids salvos resolvidos dos dois lados (interna e externa), na ordem do save. */
	@GetMapping("/saved")
	PageResponse<JobView> saved(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String viewer = viewerId(sessionToken);
		if (viewer == null) {
			throw new com.socialcoisa.rede.shared.web.ApiException(401, "UNAUTHENTICATED",
				"Sessao obrigatoria para ver as vagas favoritas.");
		}
		List<String> ids = jobService.savedJobIds(viewer);
		if (ids.isEmpty()) {
			return new PageResponse<>(List.of(), null);
		}
		java.util.Map<String, Job> internas = new java.util.HashMap<>();
		for (Job job : jobService.findByIds(ids)) {
			internas.put(job.id(), job);
		}
		java.util.Map<String, ExternalJob> externas = new java.util.HashMap<>();
		for (ExternalJob ext : externalJobRepository.findByIds(ids)) {
			externas.put(ext.id(), ext);
		}
		List<JobView> views = new java.util.ArrayList<>();
		for (String id : ids) {
			Job interna = internas.get(id);
			if (interna != null) {
				JobView view = toView(interna, viewer, false, true, 0);
				views.add(view);
				continue;
			}
			ExternalJob ext = externas.get(id);
			if (ext != null) {
				JobView base = toExternalView(ext);
				views.add(new JobView(base.id(), base.title(), base.company(), base.logo(), base.location(),
					base.type(), base.level(), base.salary(), base.currency(), base.skills(), base.postedAt(),
					base.applicationsCount(), base.applied(), true, false, 0, null, base.applicationFields(),
					base.status(), base.closedAt(), base.sourceType(), base.sourceUrl(), base.sourcePlatform(),
					base.applyEmail(), base.description(), base.salaryMin(), base.salaryMax(),
					base.salaryPeriod(), base.salaryLabel(), base.salaryNotInformed(), null,
					base.externalDetails()));
			}
		}
		return new PageResponse<>(views, null);
	}

	/** Os agregadores das externas (o filtro da aba). Cache do adapter ja cobre. */
	@GetMapping("/platforms")
	List<String> platforms() {
		// "Comunidade" é a origem das cadastradas no Énóis: tem filtro próprio e não entra na
		// lista de agregadores.
		return externalJobRepository.distinctPlatforms().stream()
			.filter(name -> !com.socialcoisa.rede.jobs.application.ExternalJobSubmissionService.COMMUNITY_PLATFORM
				.equals(name))
			.toList();
	}

	/**
	 * Cadastra uma vaga EXTERNA (achada em outro site): o enois lista, e o "Candidatar-se" vai para
	 * o link de origem. Mesmo gate de reputacao da vaga da casa, dedupe por URL e idempotencia.
	 */
	@PostMapping("/external")
	ResponseEntity<String> createExternal(@RequestBody CreateExternalJobRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of(
			"title", body.title() == null ? "" : body.title(),
			"company", body.company() == null ? "" : body.company(),
			"location", body.location() == null ? "" : body.location(),
			"url", body.url() == null ? "" : body.url()));
		return idempotency.execute(user.id(), "POST", "/api/jobs/external", idempotencyKey, hash, () -> {
			ExternalJob job = externalJobSubmission.submit(user.id().value(), body.title(), body.company(),
				body.location(), body.url(), body.description(), body.salaryMin(), body.salaryMax(),
				body.salaryPeriod(), body.applyEmail(), body.communityAgreement(),
				Boolean.TRUE.equals(body.salaryNotInformed()));
			return new CommandResponse(201, toExternalView(job, true));
		});
	}

	/** As vagas externas que EU cadastrei (a tela lista e permite excluir). */
	@GetMapping("/external/mine")
	List<JobView> myExternalJobs(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return externalJobSubmission.mine(user.id().value()).stream()
			.map(job -> toExternalView(job, true))
			.toList();
	}

	/**
	 * Prévia do e-mail de candidatura a uma vaga externa: destinatário, assunto, corpo e se há
	 * currículo para anexar. Nada é enviado — é o que a pessoa revisa antes de confirmar.
	 */
	@GetMapping("/external/{externalJobId}/application")
	ExternalApplicationDraftView externalApplicationDraft(@PathVariable String externalJobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		var draft = externalJobApplication.draft(externalJobId, user.id().value());
		return new ExternalApplicationDraftView(draft.recipientEmail(), draft.subject(), draft.body(),
			draft.resumeAvailable(), draft.resumeName(), draft.jobTitle(), draft.company(), draft.jobUrl());
	}

	/**
	 * Envia a candidatura pelo Énóis, com o currículo anexado de verdade.
	 *
	 * O corpo exige `confirmed = true`: sem confirmação explícita nada sai. O retorno diz se o
	 * anexo foi junto — quando o download do currículo falha, o e-mail sai sem ele e a resposta
	 * informa `resumeAttached = false` (nunca o contrário).
	 */
	@PostMapping("/external/{externalJobId}/application")
	ResponseEntity<String> applyExternal(@PathVariable String externalJobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody(required = false) ExternalApplyRequest body) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("externalJobId", externalJobId,
			"confirmed", body != null && Boolean.TRUE.equals(body.confirmed()),
			"message", body == null || body.message() == null ? "" : body.message()));
		return idempotency.execute(user.id(), "POST", "/api/jobs/external/{externalJobId}/application",
			idempotencyKey, hash, () -> {
				var application = externalJobApplication.apply(externalJobId, user.id().value(),
					body != null && Boolean.TRUE.equals(body.confirmed()),
					body == null ? null : body.message());
				return new CommandResponse(201, new ExternalApplicationView(application.id(),
					application.externalJobId(), application.recipientEmail(), application.subject(),
					application.resumeAttached(), application.resumeName(), application.status(),
					application.appliedAt().toString()));
			});
	}

	/** Histórico das candidaturas externas enviadas pelo Énóis. */
	@GetMapping("/external/applications/mine")
	List<ExternalApplicationView> myExternalApplications(
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return externalJobApplication.mine(user.id().value(), limit).stream()
			.map(application -> new ExternalApplicationView(application.id(), application.externalJobId(),
				application.recipientEmail(), application.subject(), application.resumeAttached(),
				application.resumeName(), application.status(), application.appliedAt().toString()))
			.toList();
	}

	/** Exclui uma vaga externa CADASTRADA POR MIM (as importadas nao tem dono). */
	@DeleteMapping("/external/{externalJobId}")
	ResponseEntity<Void> deleteExternal(@PathVariable String externalJobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		externalJobSubmission.remove(user.id().value(), externalJobId);
		return ResponseEntity.noContent().build();
	}

	/** Vagas publicadas pelo usuario autenticado (com o total de candidaturas recebidas). */
	@GetMapping("/mine")
	PageResponse<JobView> mine(@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		limit = pageLimit(limit);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Job> jobs = jobService.listMine(user.id().value(), before, limit);
		return page(jobs, limit, user.id().value(), PageKind.MINE, false, null);
	}

	/** Vagas em que o usuario autenticado se candidatou. */
	@GetMapping("/applications")
	PageResponse<JobView> applications(@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		limit = pageLimit(limit);
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Job> jobs = jobService.listApplications(user.id().value(), before, limit);
		return page(jobs, limit, user.id().value(), PageKind.APPLICATIONS, false, null);
	}

	@GetMapping("/companies")
	List<Map<String, Object>> companies(@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "60") int limit) {
		// A `origem` separa as duas abas da tela de empresas (Énóis × plataformas externas):
		// sem ela no corpo, a aba "Plataformas externas" ficava sempre vazia, porque o front
		// filtra por `c.origem === "externa"` — e um campo ausente nunca é "externa".
		return companyDirectory.directory(query, Math.min(Math.max(limit, 1), 200)).stream()
			.map(c -> Map.<String, Object>of(
				"name", c.name(),
				"logo", c.logo() == null ? "" : c.logo(),
				"openJobs", c.openJobs(),
				"origem", c.origem() == null ? CompanyDirectoryReader.ORIGEM_ENOIS : c.origem()))
			.toList();
	}

	@GetMapping("/{jobId}")
	JobView job(@PathVariable String jobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		String viewer = viewerId(sessionToken);
		// A listagem publica inclui vagas externas persistidas em tabela separada.
		// Seus identificadores pertencem ao namespace ext_; elas nao aceitam candidatura interna.
		if (externalListingEnabled && jobId.startsWith("ext_")) {
			var external = externalJobRepository.findById(jobId);
			if (external.isPresent()) return toExternalView(external.get(), false, viewer != null);
		}
		Job job = jobService.getById(jobId);
		boolean owner = viewer != null && viewer.equals(job.postedBy());
		// O dono NUNCA e candidato na propria vaga (o apply bloqueia isso). Se houver uma
		// candidatura orfa (criada antes da regra), ela nao pode marcar o dono como "Inscrito".
		boolean applied = !owner && viewer != null
			&& jobService.appliedJobIds(viewer, List.of(job.id())).contains(job.id());
		boolean saved = viewer != null && jobService.savedJobIds(viewer, List.of(job.id())).contains(job.id());
		return toView(job, viewer, applied, saved, owner ? jobService.applicationsCount(job.id()) : 0);
	}

	@PutMapping("/{jobId}")
	ResponseEntity<JobView> update(@PathVariable String jobId, @RequestBody CreateJobRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Job job = jobService.update(jobId, body.title(), body.company(), body.logo(), body.location(), body.type(),
			body.level(), body.salary(), body.currency(), body.salaryMin(), body.salaryMax(), body.salaryPeriod(),
			body.description(), body.skills(), toFieldInputs(body.applicationFields()), user.id().value());
		return ResponseEntity.ok(toView(job, user.id().value(), false, false, jobService.applicationsCount(job.id())));
	}

	/**
	 * Triagem: o dono da vaga move a candidatura de estado.
	 *
	 * Usa `resolve` (que lanca) como o encerramento — autorizacao nao pode ser "nao resolvi a sessao,
	 * entao trato como se nao fosse o dono". O estado e validado no dominio, e o candidato e avisado
	 * depois de a mudanca estar persistida.
	 */
	/**
	 * "Minhas candidaturas": o que a pessoa se candidatou, com o estado e o retorno do empregador.
	 *
	 * Antes a tela listava as VAGAS (sem o estado da candidatura), entao o "nao selecionado" com o
	 * motivo so existia no aviso — quem quisesse reler tinha de garimpar a conversa.
	 */
	@GetMapping("/applications/mine")
	List<MyApplicationView> myApplications(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return jobService.myApplications(user.id().value(), Math.min(limit, 100)).stream()
			.map(application -> {
				Job job = jobService.getById(application.jobId());
				return new MyApplicationView(application.id(), application.jobId(), job.title(), job.company(),
					application.status().name(), application.feedback(),
					application.reviewedAt() != null ? application.reviewedAt().toString() : null,
					application.appliedAt().toString());
			})
			.toList();
	}

	/** Uma candidatura como o CANDIDATO a ve. */
	record MyApplicationView(String id, String jobId, String jobTitle, String jobCompany, String status,
			String feedback, String reviewedAt, String appliedAt) {
	}

	@PatchMapping("/{jobId}/applications/{applicationId}")
	ResponseEntity<ApplicationView> updateApplicationStatus(@PathVariable String jobId,
			@PathVariable String applicationId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody UpdateApplicationStatusRequest body) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		JobApplicationStatus status = parseStatus(body == null ? null : body.status());
		JobApplication updated = jobService.updateApplicationStatus(jobId, applicationId, user.id().value(), status,
			body == null ? null : body.feedback());
		return ResponseEntity.ok(new ApplicationView(updated.id(), updated.jobId(), updated.status().name(),
			updated.appliedAt().toString(), updated.answers().size(), updated.feedback(),
			updated.reviewedAt() != null ? updated.reviewedAt().toString() : null));
	}

	/**
	 * Marca a entrevista da candidatura (ou reagenda a que já está valendo). Só quem publicou a vaga.
	 * A entrevista vira compromisso na agenda dos dois lados pela porta de origem do calendário.
	 */
	@PostMapping("/{jobId}/applications/{applicationId}/interview")
	ResponseEntity<InterviewView> scheduleInterview(@PathVariable String jobId,
			@PathVariable String applicationId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody InterviewRequest body) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		JobInterview interview = interviewService.schedule(jobId, applicationId, user.id().value(),
			parseInstant(body == null ? null : body.startsAt(), "startsAt"),
			parseInstant(body == null ? null : body.endsAt(), "endsAt"),
			body == null ? null : body.timeZoneId(), body == null ? null : body.notes());
		return ResponseEntity.status(HttpStatus.CREATED).body(InterviewView.of(interview));
	}

	/** Reagenda uma entrevista existente. A origem não muda, então as duas entradas da agenda movem. */
	@PatchMapping("/{jobId}/applications/{applicationId}/interview/{interviewId}")
	ResponseEntity<InterviewView> rescheduleInterview(@PathVariable String jobId,
			@PathVariable String applicationId, @PathVariable String interviewId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody InterviewRequest body) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		JobInterview interview = interviewService.reschedule(jobId, applicationId, interviewId, user.id().value(),
			parseInstant(body == null ? null : body.startsAt(), "startsAt"),
			parseInstant(body == null ? null : body.endsAt(), "endsAt"),
			body == null ? null : body.timeZoneId(), body == null ? null : body.notes());
		return ResponseEntity.ok(InterviewView.of(interview));
	}

	/** Desmarca a entrevista e cancela os dois compromissos da agenda. */
	@DeleteMapping("/{jobId}/applications/{applicationId}/interview/{interviewId}")
	ResponseEntity<InterviewView> cancelInterview(@PathVariable String jobId,
			@PathVariable String applicationId, @PathVariable String interviewId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		JobInterview interview = interviewService.cancel(jobId, applicationId, interviewId, user.id().value());
		return ResponseEntity.ok(InterviewView.of(interview));
	}

	/** Entrevistas da candidatura: o empregador e o candidato veem; mais ninguém. */
	@GetMapping("/{jobId}/applications/{applicationId}/interview")
	List<InterviewView> interviews(@PathVariable String jobId, @PathVariable String applicationId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return interviewService.ofApplication(jobId, applicationId, user.id().value()).stream()
			.map(InterviewView::of)
			.toList();
	}

	/** Instante ISO-8601; ausente ou inválido é erro do cliente (422), não erro interno. */
	private static Instant parseInstant(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(422, "INVALID_INTERVIEW_WINDOW", "Informe " + field + " da entrevista.");
		}
		try {
			return Instant.parse(raw.trim());
		}
		catch (java.time.format.DateTimeParseException exception) {
			throw new ApiException(422, "INVALID_INTERVIEW_WINDOW", "Data/hora inválida em " + field + ": " + raw);
		}
	}

	/** Estado desconhecido e erro do cliente (422), nao erro interno. */
	private JobApplicationStatus parseStatus(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new ApiException(422, "INVALID_STATUS", "Informe o novo estado da candidatura.");
		}
		try {
			return JobApplicationStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(422, "INVALID_STATUS", "Estado de candidatura desconhecido: " + raw);
		}
	}

	/**
	 * Encerra a vaga. Usa `resolve` (que lanca) e nao o `viewerId` tolerante: autorizacao nao pode
	 * ser "nao resolvi a sessao, entao trato como se nao fosse o dono".
	 */
	@PostMapping("/{jobId}/close")
	ResponseEntity<JobView> close(@PathVariable String jobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Job job = jobService.close(jobId, user.id().value());
		return ResponseEntity.ok(
			toView(job, user.id().value(), false, false, jobService.applicationsCount(job.id())));
	}

	@DeleteMapping("/{jobId}")
	ResponseEntity<DeleteResultView> delete(@PathVariable String jobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		JobService.DeleteResult result = jobService.delete(jobId, user.id().value());
		// O aviso volta na resposta: quem excluiu com candidatos sem resposta precisa saber
		// na hora que a credibilidade é afetada (item 7 do backlog).
		return ResponseEntity.ok(new DeleteResultView(result.jobId(), result.unanswered(), result.penalized()));
	}

	record DeleteResultView(String jobId, int unanswered, boolean penalized) {
	}

	/**
	 * Candidatura. O corpo traz a mensagem opcional e as respostas dos campos que o autor definiu —
	 * os obrigatorios sao validados aqui, e a candidatura e entregue ao empregador por e-mail e DM.
	 */
	@PostMapping("/{jobId}/applications")
	ResponseEntity<String> apply(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId,
			@RequestBody(required = false) ApplyRequest body) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId, "body", body == null ? Map.of() : body));
		return idempotency.execute(user.id(), "POST", "/api/jobs/{jobId}/applications", idempotencyKey, hash,
			() -> {
				var application = jobService.apply(jobId, user.id().value(), body == null ? null : body.message(),
					toAnswerInputs(body == null ? null : body.answers()));
				return new CommandResponse(201, new ApplicationView(application.id(), jobId, application.status().name(),
					application.appliedAt().toString(), application.answers().size()));
			});
	}

	@PostMapping("/{jobId}/saves")
	ResponseEntity<String> save(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId));
		return idempotency.execute(user.id(), "POST", "/api/jobs/{jobId}/saves", idempotencyKey, hash,
			() -> {
				jobService.save(jobId, user.id().value());
				return new CommandResponse(200, new StatusResponse(jobId, "SAVED"));
			});
	}

	@DeleteMapping("/{jobId}/saves")
	ResponseEntity<String> removeSave(@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@PathVariable String jobId) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		String hash = idempotency.hash(Map.of("jobId", jobId));
		return idempotency.execute(user.id(), "DELETE", "/api/jobs/{jobId}/saves", idempotencyKey, hash,
			() -> {
				jobService.removeSave(jobId, user.id().value());
				return new CommandResponse(200, new StatusResponse(jobId, "UNSAVED"));
			});
	}

	private enum PageKind {
		LIST, MINE, APPLICATIONS
	}

	/** Teto das listagens de vagas (inclusive a aba de externas). */
	private static int pageLimit(int limit) {
		return Math.clamp(limit, 1, 100);
	}

	private PageResponse<JobView> page(List<Job> jobs, int limit, String viewer, PageKind kind,
			boolean includeExternal, String externalQuery) {
		List<String> ids = jobs.stream().map(Job::id).toList();
		boolean authenticated = viewer != null;
		Set<String> appliedIds = switch (kind) {
			case APPLICATIONS -> Set.copyOf(ids);
			case MINE -> Set.of();
			case LIST -> authenticated ? jobService.appliedJobIds(viewer, ids) : Set.of();
		};
		Set<String> savedIds = authenticated ? jobService.savedJobIds(viewer, ids) : Set.of();
		Map<String, Integer> counts = authenticated ? jobService.applicationsCountByJobIds(ids) : Map.of();
		Map<String, String> slugs = companySlugs(jobs);
		List<JobView> items = jobs.stream()
			.map(job -> {
				boolean owner = authenticated && viewer.equals(job.postedBy());
				return new JobView(job.id(), job.title(), job.company(), job.logo(), job.location(), job.type(),
					job.level(), job.salary(), job.currency(), job.skills(),
					job.postedAt() != null ? job.postedAt().toString() : null, 0,
					!owner && appliedIds.contains(job.id()), savedIds.contains(job.id()), owner,
					owner ? counts.getOrDefault(job.id(), 0) : 0, authorOf(job), fieldViews(job),
					job.status().name(), job.closedAt() != null ? job.closedAt().toString() : null,
					"INTERNAL", null, null, null,
					job.description(), formatAmount(job.salaryMin()), formatAmount(job.salaryMax()),
					job.salaryPeriod(), salaryLabel(job), false, slugs.get(job.companyId()), null);
			})
			.toList();
		String nextCursor = jobs.size() == limit
			? cursorCodec.encode(jobs.get(jobs.size() - 1).postedAt()) : null;
		// Vagas externas entram DEPOIS das internas, so na 1a pagina da busca publica, e respeitando
		// a pesquisa por titulo (elas nao tem tipo/nivel/local para filtrar).
		if (!includeExternal || !externalListingEnabled) {
			return new PageResponse<>(items, nextCursor);
		}
		List<JobView> merged = new java.util.ArrayList<>(items);
		merged.addAll(externalJobViews(externalQuery, authenticated));
		return new PageResponse<>(merged, nextCursor);
	}

	/**
	 * Aba de vagas externas: busca com filtros combinados, ordenação e cursor.
	 *
	 * A listagem geral (sem `source`) continua usando {@link #externalJobViews} — ela é um
	 * complemento da primeira página das internas, não uma busca própria.
	 */
	private PageResponse<JobView> externalPage(String query, String platform, String level, String workModel,
			String employmentType, String city, String state, String country, java.math.BigDecimal salaryMin,
			Boolean remoteOnly, String publishedAfter, String sort, String cursor, int limit,
			boolean showApplyEmail) {
		Instant before = cursorCodec.decode(cursor).orElse(null);
		Instant after = null;
		if (org.springframework.util.StringUtils.hasText(publishedAfter)) {
			try {
				after = Instant.parse(publishedAfter.trim());
			}
			catch (RuntimeException exception) {
				throw new ApiException(400, "INVALID_DATE", "Data de publicação inválida.");
			}
		}
		String normalizedSort = normalizeSort(sort);
		String requestedPlatform = blankToNull(platform);
		// A aba de externas não mistura as vagas cadastradas pela comunidade (têm aba própria) —
		// MAS, quando a própria comunidade é a plataforma pedida, excluí-la daria lista vazia.
		String excludedPlatform = requestedPlatform == null
			? com.socialcoisa.rede.jobs.application.ExternalJobSubmissionService.COMMUNITY_PLATFORM
			: null;
		var search = new com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch(
			org.springframework.util.StringUtils.hasText(query) ? query.trim() : null,
			requestedPlatform, excludedPlatform,
			normalizeLevel(level), normalizeWorkModel(workModel),
			org.springframework.util.StringUtils.hasText(employmentType) ? employmentType.trim().toUpperCase() : null,
			blankToNull(city), blankToNull(state), blankToNull(country), salaryMin, remoteOnly, after,
			normalizedSort, before, limit);
		List<ExternalJob> found = externalJobRepository.search(search);
		List<JobView> items = found.stream()
			.map(job -> toExternalView(job, false, showApplyEmail)).toList();
		// Cursor só na ordenação por data: nas outras, a posição não é estável entre páginas.
		String nextCursor = null;
		if (found.size() == limit
				&& com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.SORT_RECENT
					.equals(normalizedSort)) {
			var last = found.getLast();
			nextCursor = last.details() == null ? null : cursorCodec.encode(last.details().publishedAt());
		}
		return new PageResponse<>(items, nextCursor);
	}

	private static String blankToNull(String value) {
		return org.springframework.util.StringUtils.hasText(value) ? value.trim() : null;
	}

	/** Ordenação desconhecida cai no padrão em vez de dar 400: é preferência, não filtro. */
	private static String normalizeSort(String sort) {
		if (!org.springframework.util.StringUtils.hasText(sort)) {
			return com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.SORT_RECENT;
		}
		String value = sort.trim().toLowerCase(java.util.Locale.ROOT);
		return switch (value) {
			case com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.SORT_SALARY,
				com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.SORT_RELEVANCE -> value;
			default -> com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.SORT_RECENT;
		};
	}

	/** Nível fora do conjunto aceito é ignorado (não é erro do usuário, é filtro sem efeito). */
	private static String normalizeLevel(String level) {
		if (!org.springframework.util.StringUtils.hasText(level)) {
			return null;
		}
		String value = level.trim().toUpperCase(java.util.Locale.ROOT);
		return com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.LEVELS.contains(value) ? value : null;
	}

	private static String normalizeWorkModel(String workModel) {
		if (!org.springframework.util.StringUtils.hasText(workModel)) {
			return null;
		}
		String value = workModel.trim().toUpperCase(java.util.Locale.ROOT);
		return com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.WORK_MODELS.contains(value)
			? value : null;
	}

	/**
	 * Vagas externas JA SALVAS no banco (tabela propria, atualizadas pelo worker da meia-noite) —
	 * nao buscamos a fonte a cada requisicao. Entram DEPOIS das internas.
	 */
	private List<JobView> externalJobViews(String query, boolean showApplyEmail) {
		// A listagem de "externas" (agregadores) NAO mistura as criadas pela comunidade: elas têm
		// filtro próprio ("Vaga externa criada no Énóis" = plataforma Comunidade). A busca já
		// filtra as removidas/expiradas — o complemento da primeira página não pode mostrá-las.
		var search = com.socialcoisa.rede.jobs.application.port.out.ExternalJobSearch.recent(
			org.springframework.util.StringUtils.hasText(query) ? query.trim() : null, null,
			com.socialcoisa.rede.jobs.application.ExternalJobSubmissionService.COMMUNITY_PLATFORM,
			externalListingLimit);
		return externalJobRepository.search(search).stream()
			.map(job -> toExternalView(job, false, showApplyEmail)).toList();
	}

	private JobView toExternalView(ExternalJob job) {
		return toExternalView(job, false, true);
	}

	/** `owner`: a vaga externa foi cadastrada por quem esta vendo (a tela mostra "excluir"). */
	private JobView toExternalView(ExternalJob job, boolean owner) {
		return toExternalView(job, owner, true);
	}

	/**
	 * `showApplyEmail`: o e-mail de candidatura so vai para quem esta autenticado. A listagem
	 * publica de vagas externas expunha o endereco para qualquer visitante.
	 */
	private JobView toExternalView(ExternalJob job, boolean owner, boolean showApplyEmail) {
		return new JobView(job.id(), job.title(), job.company(), null, job.location(), null, null,
			job.salary(), job.currency(), List.of(),
			job.importedAt() != null ? job.importedAt().toString() : null, 0, false, false, owner, 0, null,
			List.of(), "OPEN", null, "EXTERNAL", job.url(), job.platform(),
			showApplyEmail ? job.applyEmail() : null,
			job.description(), job.salaryMin(), job.salaryMax(), job.salaryPeriod(), externalSalaryLabel(job),
			job.salaryNotInformed(), null, ExternalDetailsView.of(job));
	}

	private JobView toView(Job job, String viewer, boolean applied, boolean saved, int applicationsCount) {
		return new JobView(job.id(), job.title(), job.company(), job.logo(), job.location(), job.type(), job.level(),
			job.salary(), job.currency(), job.skills(), job.postedAt() != null ? job.postedAt().toString() : null, 0,
			applied, saved, viewer != null && viewer.equals(job.postedBy()), applicationsCount, authorOf(job),
			fieldViews(job), job.status().name(), job.closedAt() != null ? job.closedAt().toString() : null,
			"INTERNAL", null, null, null,
			job.description(), formatAmount(job.salaryMin()), formatAmount(job.salaryMax()),
			job.salaryPeriod(), salaryLabel(job), false, companySlugs(List.of(job)).get(job.companyId()), null);
	}

	/** Slug of the company page each job is linked to, in one read (jobs without a page are left out). */
	private Map<String, String> companySlugs(List<Job> jobs) {
		Set<String> ids = jobs.stream().map(Job::companyId).filter(java.util.Objects::nonNull)
			.collect(java.util.stream.Collectors.toSet());
		Map<String, String> slugs = new java.util.HashMap<>();
		if (!ids.isEmpty()) {
			companyService.byIds(ids).forEach(company -> slugs.put(company.id(), company.slug()));
		}
		return slugs;
	}

	/** Faixa da vaga nova em pt-BR: "R$ 1.500 / mês" ou "de R$ 1.500 até R$ 2.000 / mês". */
	private String salaryLabel(Job job) {
		if (job.salaryMin() == null) {
			return null;
		}
		String symbol = currencySymbol(job.currency());
		String min = symbol + " " + formatAmount(job.salaryMin());
		if (job.salaryMax() != null) {
			return "de " + min + " até " + symbol + " " + formatAmount(job.salaryMax()) + " / "
				+ periodLabel(job.salaryPeriod());
		}
		return min + " / " + periodLabel(job.salaryPeriod());
	}

	/** Faixa da vaga externa cadastrada à mão ("de R$ 1.500 até R$ 2.000 / mês"). */
	private static String externalSalaryLabel(ExternalJob job) {
		if (job.salaryNotInformed()) {
			return "Salário não informado";
		}
		if (!org.springframework.util.StringUtils.hasText(job.salaryMin())) {
			return null;
		}
		String symbol = currencySymbol(job.currency());
		String min = symbol + " " + amountText(job.salaryMin());
		if (org.springframework.util.StringUtils.hasText(job.salaryMax())) {
			return "de " + min + " até " + symbol + " " + amountText(job.salaryMax()) + " / "
				+ periodLabel(job.salaryPeriod());
		}
		return min + " / " + periodLabel(job.salaryPeriod());
	}

	private static String amountText(String value) {
		try {
			return formatAmount(new java.math.BigDecimal(value.trim().replace(",", ".")));
		}
		catch (RuntimeException invalid) {
			return value.trim();
		}
	}

	private static String currencySymbol(String currency) {
		return switch (currency == null ? "BRL" : currency.toUpperCase(java.util.Locale.ROOT)) {
			case "USD" -> "US$";
			case "EUR" -> "€";
			default -> "R$";
		};
	}

	/** Inteiro sem centavos ("1.500"); com centavos, sempre dois dígitos ("2.500,50"). */
	private static String formatAmount(java.math.BigDecimal value) {
		if (value == null) {
			return null;
		}
		java.text.NumberFormat nf = java.text.NumberFormat
			.getNumberInstance(new java.util.Locale("pt", "BR"));
		if (value.stripTrailingZeros().scale() <= 0) {
			nf.setMaximumFractionDigits(0);
		} else {
			nf.setMinimumFractionDigits(2);
			nf.setMaximumFractionDigits(2);
		}
		return nf.format(value);
	}

	private static String periodLabel(String period) {
		return switch (period == null ? "MONTH" : period) {
			case "HOUR" -> "hora";
			case "DAY" -> "dia";
			case "YEAR" -> "ano";
			default -> "mês";
		};
	}

	/** Quem criou a vaga (com reputacao e verificacao): o candidato precisa saber com quem fala. */
	private AuthorView authorOf(Job job) {
		var profile = userProfileRepository.findById(job.postedBy()).orElse(null);
		if (profile == null) {
			return null;
		}
		return new AuthorView(profile.id(), profile.username(), profile.name(), profile.avatar(), profile.headline(),
			reputationService.reputationOf(profile.id()), profile.identityVerified(), NameStyleView.from(profile));
	}

	private List<ApplicationFieldView> fieldViews(Job job) {
		return job.applicationFields().stream()
			.map(field -> new ApplicationFieldView(field.id(), field.label(), field.type().name(), field.required()))
			.toList();
	}

	/** Resolve o usuario quando ha sessao; listas publicas seguem funcionando sem login. */
	private String viewerId(String sessionToken) {
		try {
			return currentUserResolver.resolve(sessionToken).id().value();
		}
		catch (RuntimeException exception) {
			return null;
		}
	}

	record JobView(String id, String title, String company, String logo, String location, String type, String level,
			String salary, String currency, List<String> skills, String postedAt, int matchScore, boolean applied,
			boolean saved, boolean owner, int applicationsCount, AuthorView author, List<ApplicationFieldView>
			applicationFields, String status, String closedAt, String sourceType, String sourceUrl,
			String sourcePlatform, String applyEmail,
			// Valor estruturado da vaga nova (item 5 do backlog). `salaryLabel` já vem pronto
			// ("R$ 1.500 / mês" ou "de R$ 1.500 até R$ 2.000 / mês"); vagas antigas trazem só
			// o texto livre em `salary` e nulos aqui.
			String description, String salaryMin, String salaryMax, String salaryPeriod, String salaryLabel,
			boolean salaryNotInformed,
			// The company page the job is linked to (null when it is not linked).
			String companySlug,
			// Metadados estruturados das vagas externas (nulo nas internas): publicação, modelo de
			// trabalho, localidade, faixa numérica, atribuição e ciclo de vida.
			ExternalDetailsView externalDetails) {
	}

	/**
	 * O que a fonte externa informou de estruturado. Campo nulo = a fonte não informou; nada aqui
	 * é inferido. `lastSeenAt` é a última verificação de que a vaga ainda existe na origem.
	 */
	record ExternalDetailsView(String publishedAt, String lastSeenAt, String employmentType, String level,
			String workModel, String city, String state, String country, java.math.BigDecimal salaryMinAmount,
			java.math.BigDecimal salaryMaxAmount, String salaryCurrency, String salaryPeriodCode,
			String attributionUrl, String expiresAt, String removedAt) {

		static ExternalDetailsView of(ExternalJob job) {
			var details = job.details();
			if (details == null) {
				return null;
			}
			return new ExternalDetailsView(
				details.publishedAt() != null ? details.publishedAt().toString() : null,
				details.lastSeenAt() != null ? details.lastSeenAt().toString() : null,
				details.employmentType(), details.level(), details.workModel(), details.city(), details.state(),
				details.country(), details.salaryMinAmount(), details.salaryMaxAmount(), details.salaryCurrency(),
				details.salaryPeriodCode(), details.attributionUrl(),
				details.expiresAt() != null ? details.expiresAt().toString() : null,
				details.removedAt() != null ? details.removedAt().toString() : null);
		}
	}

	/** Quem publicou a vaga, com o que sustenta a credibilidade dele. */
	record AuthorView(String id, String username, String name, String avatar, String headline, int reputation,
			boolean identityVerified, NameStyleView nameStyle) {
	}

	record ApplicationFieldView(String id, String label, String type, boolean required) {
	}

	record CreateJobRequest(String title, String company, String logo, String location, String type, String level,
			String salary, String currency, String salaryMin, String salaryMax, String salaryPeriod,
			String description, List<String> skills, boolean communityAgreement,
			List<ApplicationFieldRequest> applicationFields,
			// Optional on create: post for this company page (RECRUITER or above). Ignored on edit.
			String companyId) {
	}

	/** Cadastro de vaga EXTERNA: so o que a listagem mostra e o link para onde a candidatura vai. */
	record CreateExternalJobRequest(String title, String company, String location, String url,
			String description, String salaryMin, String salaryMax, String salaryPeriod, String applyEmail,
			boolean communityAgreement, Boolean salaryNotInformed) {
	}

	/** O e-mail de candidatura pronto para revisão (nada foi enviado ainda). */
	record ExternalApplicationDraftView(String recipientEmail, String subject, String body,
			boolean resumeAvailable, String resumeName, String jobTitle, String company, String jobUrl) {
	}

	/** Corpo do envio: a confirmação é obrigatória e o texto extra é opcional. */
	record ExternalApplyRequest(Boolean confirmed, String message) {
	}

	/** Comprovante da candidatura enviada: para onde foi, com que assunto e se o anexo foi junto. */
	record ExternalApplicationView(String id, String externalJobId, String recipientEmail, String subject,
			boolean resumeAttached, String resumeName, String status, String appliedAt) {
	}

	record ApplicationFieldRequest(String label, String type, boolean required) {
	}

	record ApplyRequest(String message, List<AnswerRequest> answers) {
	}

	/** `feedback` e obrigatorio ao reprovar (o dominio recusa sem ele). */
	record UpdateApplicationStatusRequest(String status, String feedback) {
	}

	record InterviewRequest(String startsAt, String endsAt, String timeZoneId, String notes) {
	}

	/** Entrevista como a API devolve: instantes em ISO-8601 UTC e o fuso de quem marcou. */
	record InterviewView(String id, String applicationId, String jobId, String candidateId, String status,
			String startsAt, String endsAt, String timeZoneId, String notes, String createdAt, String updatedAt,
			String cancelledAt) {

		static InterviewView of(JobInterview interview) {
			return new InterviewView(interview.id(), interview.applicationId(), interview.jobId(),
				interview.candidateId(), interview.status().name(), interview.startsAt().toString(),
				interview.endsAt().toString(), interview.zone().getId(), interview.notes(),
				interview.createdAt().toString(), interview.updatedAt().toString(),
				interview.cancelledAt() == null ? null : interview.cancelledAt().toString());
		}
	}

	record AnswerRequest(String fieldId, String value) {
	}

	/** Candidatura como o empregador ve: o retrato do candidato + as respostas. */
	record ApplicationView(String id, String jobId, String status, String appliedAt, int answersCount,
			CandidateView candidate, String message, List<AnswerView> answers, String feedback, String reviewedAt,
			InterviewView interview) {

		ApplicationView(String id, String jobId, String status, String appliedAt, int answersCount) {
			this(id, jobId, status, appliedAt, answersCount, null, null, List.of(), null, null, null);
		}

		ApplicationView(String id, String jobId, String status, String appliedAt, int answersCount,
				String feedback, String reviewedAt) {
			this(id, jobId, status, appliedAt, answersCount, null, null, List.of(), feedback, reviewedAt, null);
		}
	}

	record CandidateView(String userId, String name, String socialName, String username, String headline, String location, String avatar,
			List<String> skills, int experienceYears, int experiencesCount, int reputation, boolean identityVerified,
			String resumeUrl, String resumeName, String identity, String industry) {
	}

	record AnswerView(String label, String value) {
	}

	private List<JobService.JobFieldInput> toFieldInputs(List<ApplicationFieldRequest> fields) {
		if (fields == null) {
			return List.of();
		}
		return fields.stream()
			.map(field -> new JobService.JobFieldInput(field.label(), field.type(), field.required()))
			.toList();
	}

	private List<JobService.JobAnswerInput> toAnswerInputs(List<AnswerRequest> answers) {
		if (answers == null) {
			return List.of();
		}
		return answers.stream()
			.map(answer -> new JobService.JobAnswerInput(answer.fieldId(), answer.value()))
			.toList();
	}

	/** Le o retrato do candidato congelado na candidatura (JSON gravado no envio). */
	private CandidateView toCandidateView(com.socialcoisa.rede.jobs.domain.JobApplication application) {
		try {
			var snapshot = objectMapper.readTree(application.candidateSnapshot() == null ? "{}" : application.candidateSnapshot());
			return new CandidateView(
				application.userId(),
				snapshot.path("name").asText(""),
				snapshot.path("socialName").asText(""),
				snapshot.path("username").asText(""),
				snapshot.path("headline").asText(""),
				snapshot.path("location").asText(""),
				snapshot.path("avatar").asText(""),
				java.util.stream.StreamSupport.stream(snapshot.path("skills").spliterator(), false)
					.map(node -> node.asText("")).filter(text -> !text.isBlank()).toList(),
				snapshot.path("experienceYears").asInt(0),
				snapshot.path("experiencesCount").asInt(0),
				snapshot.path("reputation").asInt(0),
				snapshot.path("identityVerified").asBoolean(false),
				snapshot.path("resumeUrl").asText(""),
				snapshot.path("resumeName").asText(""),
				snapshot.path("identity").asText("SOCIAL"),
				snapshot.path("industry").asText(""));
		}
		catch (Exception exception) {
			return new CandidateView(application.userId(), "", "", "", "", "", "", List.of(), 0, 0, 0, false, "", "",
				"SOCIAL", "");
		}
	}

}

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
import com.socialcoisa.rede.jobs.application.JobService;
import com.socialcoisa.rede.jobs.domain.Job;
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
	private final boolean externalListingEnabled;
	private final int externalListingLimit;
	private final tools.jackson.databind.ObjectMapper objectMapper;

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
			com.socialcoisa.rede.jobs.application.ExternalJobSubmissionService externalJobSubmission) {
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
	}

	/** Candidaturas recebidas (somente quem publicou a vaga). */
	@GetMapping("/{jobId}/applications")
	List<ApplicationView> applications(@PathVariable String jobId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestParam(defaultValue = "50") int limit) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		return jobService.applicationsOf(jobId, user.id().value(), limit).stream()
			.map(application -> new ApplicationView(application.id(), application.jobId(), application.status().name(),
				application.appliedAt().toString(), application.answers().size(),
				toCandidateView(application), application.message(),
				application.answers().stream()
					.map(answer -> new AnswerView(answer.label(), answer.value()))
					.toList(),
				application.feedback(),
				application.reviewedAt() != null ? application.reviewedAt().toString() : null))
			.toList();
	}

	@PostMapping
	ResponseEntity<JobView> create(@RequestBody CreateJobRequest body,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(sessionToken);
		Job job = jobService.create(body.title(), body.company(), body.logo(), body.location(), body.type(), body.level(),
			body.salary(), body.currency(), body.salaryMin(), body.salaryMax(), body.salaryPeriod(),
			body.description(), body.skills(), toFieldInputs(body.applicationFields()), user.id().value(),
			body.communityAgreement());
		return ResponseEntity.status(HttpStatus.CREATED).body(toView(job, user.id().value(), false, false, 0));
	}

	@GetMapping
	PageResponse<JobView> jobs(@RequestParam(required = false) String query,
			@RequestParam(required = false) String type, @RequestParam(required = false) String level,
			@RequestParam(required = false) String location,
			@RequestParam(required = false) List<String> skills,
			@RequestParam(required = false) String source, @RequestParam(required = false) String platform,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		// As ABAS: EXTERNAL devolve SO as externas (com filtro de agregador); INTERNAL nunca
		// mistura. Sem `source` mantem o comportamento antigo (internas + externas na 1a pagina).
		boolean soExternas = "EXTERNAL".equalsIgnoreCase(source);
		boolean soInternas = "INTERNAL".equalsIgnoreCase(source);
		if (soExternas) {
			List<JobView> externas = org.springframework.util.StringUtils.hasText(platform)
				? externalJobRepository.findByPlatform(platform.trim(), query, limit).stream()
					.map(this::toExternalView).toList()
				: externalJobViews(query);
			return new PageResponse<>(externas, null);
		}
		Instant before = cursorCodec.decode(cursor).orElse(null);
		List<Job> jobs = jobService.list(query, type, level, location, skills, before, limit);
		// Externas nao tem tipo/nivel/local/skills: se o usuario filtrou por isso, elas nao entram.
		boolean structuralFilters = org.springframework.util.StringUtils.hasText(type)
			|| org.springframework.util.StringUtils.hasText(level)
			|| org.springframework.util.StringUtils.hasText(location)
			|| (skills != null && !skills.isEmpty());
		boolean firstPage = cursor == null;
		return page(jobs, limit, viewerId(sessionToken), PageKind.LIST,
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
					base.salaryPeriod(), base.salaryLabel(), base.salaryNotInformed()));
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
		// A listagem publica inclui vagas externas persistidas em tabela separada.
		// Seus identificadores pertencem ao namespace ext_; elas nao aceitam candidatura interna.
		if (externalListingEnabled && jobId.startsWith("ext_")) {
			var external = externalJobRepository.findById(jobId);
			if (external.isPresent()) return toExternalView(external.get());
		}
		String viewer = viewerId(sessionToken);
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
					job.salaryPeriod(), salaryLabel(job), false);
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
		merged.addAll(externalJobViews(externalQuery));
		return new PageResponse<>(merged, nextCursor);
	}

	/**
	 * Vagas externas JA SALVAS no banco (tabela propria, atualizadas pelo worker da meia-noite) —
	 * nao buscamos a fonte a cada requisicao. Entram DEPOIS das internas.
	 */
	private List<JobView> externalJobViews(String query) {
		List<JobView> external = new java.util.ArrayList<>();
		// A listagem de "externas" (agregadores) NAO mistura as criadas pela comunidade: elas têm
		// filtro próprio ("Vaga externa criada no Énóis" = plataforma Comunidade).
		var found = org.springframework.util.StringUtils.hasText(query)
			? externalJobRepository.searchExcludingPlatform(
				com.socialcoisa.rede.jobs.application.ExternalJobSubmissionService.COMMUNITY_PLATFORM,
				query.trim(), externalListingLimit)
			: externalJobRepository.findRecentExcludingPlatform(
				com.socialcoisa.rede.jobs.application.ExternalJobSubmissionService.COMMUNITY_PLATFORM,
				externalListingLimit);
		for (ExternalJob job : found) {
			external.add(toExternalView(job));
		}
		return external;
	}

	private JobView toExternalView(ExternalJob job) {
		return toExternalView(job, false);
	}

	/** `owner`: a vaga externa foi cadastrada por quem esta vendo (a tela mostra "excluir"). */
	private JobView toExternalView(ExternalJob job, boolean owner) {
		return new JobView(job.id(), job.title(), job.company(), null, job.location(), null, null,
			job.salary(), job.currency(), List.of(),
			job.importedAt() != null ? job.importedAt().toString() : null, 0, false, false, owner, 0, null,
			List.of(), "OPEN", null, "EXTERNAL", job.url(), job.platform(), job.applyEmail(),
			job.description(), job.salaryMin(), job.salaryMax(), job.salaryPeriod(), externalSalaryLabel(job),
			job.salaryNotInformed());
	}

	private JobView toView(Job job, String viewer, boolean applied, boolean saved, int applicationsCount) {
		return new JobView(job.id(), job.title(), job.company(), job.logo(), job.location(), job.type(), job.level(),
			job.salary(), job.currency(), job.skills(), job.postedAt() != null ? job.postedAt().toString() : null, 0,
			applied, saved, viewer != null && viewer.equals(job.postedBy()), applicationsCount, authorOf(job),
			fieldViews(job), job.status().name(), job.closedAt() != null ? job.closedAt().toString() : null,
			"INTERNAL", null, null, null,
			job.description(), formatAmount(job.salaryMin()), formatAmount(job.salaryMax()),
			job.salaryPeriod(), salaryLabel(job), false);
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
			boolean salaryNotInformed) {
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
			List<ApplicationFieldRequest> applicationFields) {
	}

	/** Cadastro de vaga EXTERNA: so o que a listagem mostra e o link para onde a candidatura vai. */
	record CreateExternalJobRequest(String title, String company, String location, String url,
			String description, String salaryMin, String salaryMax, String salaryPeriod, String applyEmail,
			boolean communityAgreement, Boolean salaryNotInformed) {
	}

	record ApplicationFieldRequest(String label, String type, boolean required) {
	}

	record ApplyRequest(String message, List<AnswerRequest> answers) {
	}

	/** `feedback` e obrigatorio ao reprovar (o dominio recusa sem ele). */
	record UpdateApplicationStatusRequest(String status, String feedback) {
	}

	record AnswerRequest(String fieldId, String value) {
	}

	/** Candidatura como o empregador ve: o retrato do candidato + as respostas. */
	record ApplicationView(String id, String jobId, String status, String appliedAt, int answersCount,
			CandidateView candidate, String message, List<AnswerView> answers, String feedback, String reviewedAt) {

		ApplicationView(String id, String jobId, String status, String appliedAt, int answersCount) {
			this(id, jobId, status, appliedAt, answersCount, null, null, List.of(), null, null);
		}

		ApplicationView(String id, String jobId, String status, String appliedAt, int answersCount,
				String feedback, String reviewedAt) {
			this(id, jobId, status, appliedAt, answersCount, null, null, List.of(), feedback, reviewedAt);
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

package com.socialcoisa.rede.profile.adapters.in.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.util.StringUtils;

import com.socialcoisa.rede.profile.application.UpdateProfileCommand;
import com.socialcoisa.rede.profile.application.port.in.GetMeUseCase;
import com.socialcoisa.rede.profile.application.port.in.GetUserProfileUseCase;
import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase;
import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase.CertificationView;
import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase.EducationView;
import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase.ExperienceView;
import com.socialcoisa.rede.profile.application.port.in.UpdateProfileUseCase;
import com.socialcoisa.rede.idempotency.application.CommandResponse;
import com.socialcoisa.rede.idempotency.application.IdempotencyApplicationService;
import com.socialcoisa.rede.profile.application.ProfileService;
import com.socialcoisa.rede.network.application.NetworkService;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;
import com.socialcoisa.rede.shared.web.ApiException;

@RestController
class ProfileController {

	private final GetMeUseCase getMeUseCase;
	private final GetUserProfileUseCase getUserProfileUseCase;
	private final UpdateProfileUseCase updateProfileUseCase;
	private final ManageProfileExtensionUseCase profileExtensionUseCase;
	private final ProfileService profileService;
	private final NetworkService networkService;
	private final UserProfileRepository userProfileRepository;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	ProfileController(GetMeUseCase getMeUseCase, GetUserProfileUseCase getUserProfileUseCase,
			UpdateProfileUseCase updateProfileUseCase,
			ManageProfileExtensionUseCase profileExtensionUseCase, ProfileService profileService,
			NetworkService networkService, UserProfileRepository userProfileRepository,
			CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency) {
		this.getMeUseCase = getMeUseCase;
		this.getUserProfileUseCase = getUserProfileUseCase;
		this.updateProfileUseCase = updateProfileUseCase;
		this.profileExtensionUseCase = profileExtensionUseCase;
		this.profileService = profileService;
		this.networkService = networkService;
		this.userProfileRepository = userProfileRepository;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	@GetMapping("/me")
	Object me(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return getMeUseCase.getMe(user.id().value());
	}

	/** Estado de idade do usuario logado (ECA Digital) — sem expor a data de nascimento. */
	@GetMapping("/me/age-state")
	com.socialcoisa.rede.profile.application.ProfileService.AgeStateView ageState(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return profileService.ageState(user.id().value());
	}

	/** Regularizacao da base: declara (ou corrige) a data de nascimento. */
	@PostMapping("/me/birth-date")
	com.socialcoisa.rede.profile.application.ProfileService.AgeStateView declareBirthDate(
			@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestBody BirthDateRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return profileService.declareBirthDate(user.id().value(), request.birthDate());
	}

	public record BirthDateRequest(String birthDate) {
	}

	@GetMapping("/users/{username}")
	Object user(@PathVariable String username) {
		return getUserProfileUseCase.getByUsername(username);
	}

	/** Pessoas ligadas ao perfil visitado; apenas conexoes aceitas entram na leitura publica. */
	@GetMapping("/users/{username}/connections")
	List<PublicNetworkUserView> connections(@PathVariable String username) {
		return networkUsers(username, false);
	}

	/** Seguidores do perfil visitado: quem enviou uma conexao aceita para ele. */
	@GetMapping("/users/{username}/followers")
	List<PublicNetworkUserView> followers(@PathVariable String username) {
		return networkUsers(username, true);
	}

	private List<PublicNetworkUserView> networkUsers(String username, boolean followers) {
		UserProfile profile = userProfileRepository.findByUsername(username)
			.orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "Usuario nao encontrado."));
		List<String> ids = networkService.getConnections(profile.id()).stream()
			.filter(connection -> !followers || profile.id().equals(connection.receiverUserId()))
			.map(connection -> profile.id().equals(connection.requesterUserId())
				? connection.receiverUserId() : connection.requesterUserId())
			.distinct()
			.toList();
		Map<String, UserProfile> profiles = userProfileRepository.findAllByIds(ids).stream()
			.collect(Collectors.toMap(UserProfile::id, Function.identity()));
		return ids.stream()
			.map(profiles::get)
			.filter(Objects::nonNull)
			.map(PublicNetworkUserView::from)
			.toList();
	}

	record PublicNetworkUserView(String id, String username, String name, String headline, String avatar,
		String avatarFrame) {
		static PublicNetworkUserView from(UserProfile profile) {
			return new PublicNetworkUserView(profile.id(), profile.username(), profile.displayWithNickname(),
				profile.headline(), profile.avatar(), profile.avatarFrame());
		}
	}

	record RankingPreferenceRequest(boolean show) {
	}

	@GetMapping("/users/by-id/{userId}")
	Object userById(@PathVariable String userId) {
		return getUserProfileUseCase.getById(userId);
	}

	@GetMapping("/users/search")
	Object searchUsers(@RequestParam String q) {
		if (!StringUtils.hasText(q) || q.trim().length() < 2) {
			return List.of();
		}
		return profileService.search(q.trim());
	}

	@GetMapping("/ranking")
	Object ranking(@RequestParam(defaultValue = "20") int limit) {
		return profileService.getRanking(publicListLimit(limit));
	}

	@GetMapping("/profile/ranking")
	Object profileRanking(@RequestParam(defaultValue = "20") int limit) {
		return profileService.getRanking(publicListLimit(limit));
	}

	/**
	 * Teto da vitrine publica. Sem ele, `?limit=1000000` devolvia todos os perfis numa resposta so,
	 * sem sessao: carga pesada no banco e a base inteira raspada de uma vez.
	 */
	private static int publicListLimit(int limit) {
		return Math.min(Math.max(limit, 1), 50);
	}

	/**
	 * Ranking OPCIONAL (pedido via suporte): a secao de ranking do perfil some para quem nao
	 * curte competicao. Preferencia da conta — vale para quem LE, nao para o perfil visitado.
	 */
	@PutMapping("/me/ranking")
	ResponseEntity<String> ranking(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody RankingPreferenceRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "PUT", "/api/me/ranking", idempotencyKey, hash,
			() -> new CommandResponse(200, profileService.setShowRanking(user.id().value(), request.show())));
	}

	@PatchMapping("/me/profile")
	ResponseEntity<String> update(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody UpdateProfileRequest request) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		String hash = idempotency.hash(request);
		return idempotency.execute(user.id(), "PATCH", "/api/me/profile", idempotencyKey, hash,
			() -> new CommandResponse(200, updateProfileUseCase.update(new UpdateProfileCommand(user.id().value(),
				request.name(), request.headline(), request.company(), request.location(), request.avatar(),
				request.banner(), request.badge(), request.bio(), request.bioHtml(), request.bannerHtml(), request.skills(), request.goals(),
				request.availability(),
				request.portfolio(), request.externalLinks(), request.openToWork(), request.isMentor(),
				request.username(), request.aka(), request.resumeUrl(), request.resumeName()))));
	}

	private CurrentUser resolve(String userId, String sessionToken) {
		return currentUserResolver.resolve(userId, sessionToken);
	}

	/** Experiências para o perfil público (abaixo dos projetos); leitura aberta. */
	@GetMapping("/users/{username}/experiences")
	List<ExperienceView> experiencesByUsername(@PathVariable String username) {
		return profileExtensionUseCase.getExperiencesOf(username);
	}

	@GetMapping("/me/experiences")
	List<ExperienceView> getExperiences(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken) {
		return profileExtensionUseCase.getExperiences(resolve(userId, sessionToken).id().value());
	}

	@PostMapping("/me/experiences")
	@ResponseStatus(HttpStatus.CREATED)
	ExperienceView addExperience(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @RequestBody ExperienceRequest request) {
		return profileExtensionUseCase.addExperience(resolve(userId, sessionToken).id().value(), request.title(),
			request.company(), request.location(), request.description(), request.startDate(), request.endDate());
	}

	@PutMapping("/me/experiences/{id}")
	ExperienceView updateExperience(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id,
			@RequestBody ExperienceRequest request) {
		return profileExtensionUseCase.updateExperience(resolve(userId, sessionToken).id().value(), id, request.title(),
			request.company(), request.location(), request.description(), request.startDate(), request.endDate());
	}

	@DeleteMapping("/me/experiences/{id}")
	void removeExperience(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id) {
		profileExtensionUseCase.removeExperience(resolve(userId, sessionToken).id().value(), id);
	}

	@GetMapping("/me/education")
	List<EducationView> getEducation(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken) {
		return profileExtensionUseCase.getEducations(resolve(userId, sessionToken).id().value());
	}

	@PostMapping("/me/education")
	@ResponseStatus(HttpStatus.CREATED)
	EducationView addEducation(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @RequestBody EducationRequest request) {
		return profileExtensionUseCase.addEducation(resolve(userId, sessionToken).id().value(), request.school(),
			request.degree(), request.field(), request.description(), request.startDate(), request.endDate());
	}

	@PutMapping("/me/education/{id}")
	EducationView updateEducation(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id,
			@RequestBody EducationRequest request) {
		return profileExtensionUseCase.updateEducation(resolve(userId, sessionToken).id().value(), id, request.school(),
			request.degree(), request.field(), request.description(), request.startDate(), request.endDate());
	}

	@DeleteMapping("/me/education/{id}")
	void removeEducation(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id) {
		profileExtensionUseCase.removeEducation(resolve(userId, sessionToken).id().value(), id);
	}

	@GetMapping("/me/certifications")
	List<CertificationView> getCertifications(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken) {
		return profileExtensionUseCase.getCertifications(resolve(userId, sessionToken).id().value());
	}

	@PostMapping("/me/certifications")
	@ResponseStatus(HttpStatus.CREATED)
	CertificationView addCertification(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @RequestBody CertificationRequest request) {
		return profileExtensionUseCase.addCertification(resolve(userId, sessionToken).id().value(), request.name(),
			request.issuer(), request.description(), request.issueDate(), request.expirationDate(),
			request.credentialUrl());
	}

	@PutMapping("/me/certifications/{id}")
	CertificationView updateCertification(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id,
			@RequestBody CertificationRequest request) {
		return profileExtensionUseCase.updateCertification(resolve(userId, sessionToken).id().value(), id, request.name(),
			request.issuer(), request.description(), request.issueDate(), request.expirationDate(),
			request.credentialUrl());
	}

	@DeleteMapping("/me/certifications/{id}")
	void removeCertification(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id) {
		profileExtensionUseCase.removeCertification(resolve(userId, sessionToken).id().value(), id);
	}

	record ExperienceRequest(String title, String company, String location, String description, LocalDate startDate,
			LocalDate endDate) {
	}

	record EducationRequest(String school, String degree, String field, String description, LocalDate startDate,
			LocalDate endDate) {
	}

	record CertificationRequest(String name, String issuer, String description, LocalDate issueDate,
			LocalDate expirationDate, String credentialUrl) {
	}

}

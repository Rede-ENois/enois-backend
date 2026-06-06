package com.socialcoisa.rede.profile.adapters.in.web;

import java.time.LocalDate;
import java.util.List;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
import com.socialcoisa.rede.shared.web.CurrentUser;
import com.socialcoisa.rede.shared.web.CurrentUserResolver;

@RestController
@RequestMapping("/api")
class ProfileController {

	private final GetMeUseCase getMeUseCase;
	private final GetUserProfileUseCase getUserProfileUseCase;
	private final UpdateProfileUseCase updateProfileUseCase;
	private final ManageProfileExtensionUseCase profileExtensionUseCase;
	private final CurrentUserResolver currentUserResolver;
	private final IdempotencyApplicationService idempotency;

	ProfileController(GetMeUseCase getMeUseCase, GetUserProfileUseCase getUserProfileUseCase,
			UpdateProfileUseCase updateProfileUseCase,
			ManageProfileExtensionUseCase profileExtensionUseCase,
			CurrentUserResolver currentUserResolver,
			IdempotencyApplicationService idempotency) {
		this.getMeUseCase = getMeUseCase;
		this.getUserProfileUseCase = getUserProfileUseCase;
		this.updateProfileUseCase = updateProfileUseCase;
		this.profileExtensionUseCase = profileExtensionUseCase;
		this.currentUserResolver = currentUserResolver;
		this.idempotency = idempotency;
	}

	@GetMapping("/me")
	Object me(@RequestHeader(value = "X-User-Id", required = false) String userId,
			@RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
		CurrentUser user = currentUserResolver.resolve(userId, sessionToken);
		return getMeUseCase.getMe(user.id().value());
	}

	@GetMapping("/users/{username}")
	Object user(@PathVariable String username) {
		return getUserProfileUseCase.getByUsername(username);
	}

	@GetMapping("/users/by-id/{userId}")
	Object userById(@PathVariable String userId) {
		return getUserProfileUseCase.getById(userId);
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
				request.banner(), request.bio(), request.skills(), request.goals(), request.availability(),
				request.portfolio(), request.externalLinks(), request.openToWork(), request.isMentor()))));
	}

	private CurrentUser resolve(String userId, String sessionToken) {
		return currentUserResolver.resolve(userId, sessionToken);
	}

	@GetMapping("/me/experiences")
	List<ExperienceView> getExperiences(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken) {
		return profileExtensionUseCase.getExperiences(resolve(userId, sessionToken).id().value());
	}

	@PostMapping("/me/experiences")
	@ResponseStatus(HttpStatus.CREATED)
	ExperienceView addExperience(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @RequestBody ExperienceRequest request) {
		return profileExtensionUseCase.addExperience(resolve(userId, sessionToken).id().value(), request.title(),
			request.company(), request.location(), request.description(), request.startDate(), request.endDate());
	}

	@PutMapping("/me/experiences/{id}")
	ExperienceView updateExperience(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id,
			@RequestBody ExperienceRequest request) {
		return profileExtensionUseCase.updateExperience(resolve(userId, sessionToken).id().value(), id, request.title(),
			request.company(), request.location(), request.description(), request.startDate(), request.endDate());
	}

	@DeleteMapping("/me/experiences/{id}")
	void removeExperience(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id) {
		profileExtensionUseCase.removeExperience(resolve(userId, sessionToken).id().value(), id);
	}

	@GetMapping("/me/education")
	List<EducationView> getEducation(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken) {
		return profileExtensionUseCase.getEducations(resolve(userId, sessionToken).id().value());
	}

	@PostMapping("/me/education")
	@ResponseStatus(HttpStatus.CREATED)
	EducationView addEducation(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @RequestBody EducationRequest request) {
		return profileExtensionUseCase.addEducation(resolve(userId, sessionToken).id().value(), request.school(),
			request.degree(), request.field(), request.description(), request.startDate(), request.endDate());
	}

	@PutMapping("/me/education/{id}")
	EducationView updateEducation(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id,
			@RequestBody EducationRequest request) {
		return profileExtensionUseCase.updateEducation(resolve(userId, sessionToken).id().value(), id, request.school(),
			request.degree(), request.field(), request.description(), request.startDate(), request.endDate());
	}

	@DeleteMapping("/me/education/{id}")
	void removeEducation(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id) {
		profileExtensionUseCase.removeEducation(resolve(userId, sessionToken).id().value(), id);
	}

	@GetMapping("/me/certifications")
	List<CertificationView> getCertifications(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken) {
		return profileExtensionUseCase.getCertifications(resolve(userId, sessionToken).id().value());
	}

	@PostMapping("/me/certifications")
	@ResponseStatus(HttpStatus.CREATED)
	CertificationView addCertification(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @RequestBody CertificationRequest request) {
		return profileExtensionUseCase.addCertification(resolve(userId, sessionToken).id().value(), request.name(),
			request.issuer(), request.description(), request.issueDate(), request.expirationDate(),
			request.credentialUrl());
	}

	@PutMapping("/me/certifications/{id}")
	CertificationView updateCertification(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("X-Session-Token") String sessionToken, @PathVariable String id,
			@RequestBody CertificationRequest request) {
		return profileExtensionUseCase.updateCertification(resolve(userId, sessionToken).id().value(), id, request.name(),
			request.issuer(), request.description(), request.issueDate(), request.expirationDate(),
			request.credentialUrl());
	}

	@DeleteMapping("/me/certifications/{id}")
	void removeCertification(@RequestHeader("X-User-Id") String userId,
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

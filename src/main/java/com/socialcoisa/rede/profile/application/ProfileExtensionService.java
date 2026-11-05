package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase;
import com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.Certification;
import com.socialcoisa.rede.profile.domain.Education;
import com.socialcoisa.rede.profile.domain.Experience;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class ProfileExtensionService implements ManageProfileExtensionUseCase {

	/** Teto por categoria: currículo artificial (dezenas de entradas) deixa de virar nota. */
	private static final int MAX_ENTRIES_PER_KIND = 10;

	private final ProfileExtensionRepository repository;
	private final UserProfileRepository profiles;
	private final Clock clock;

	public ProfileExtensionService(ProfileExtensionRepository repository, UserProfileRepository profiles, Clock clock) {
		this.repository = repository;
		this.profiles = profiles;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ExperienceView> getExperiences(String userId) {
		return repository.findExperiencesByUserId(userId).stream()
			.map(this::toExperienceView)
			.toList();
	}

	/**
	 * Perfil público: o mais recente primeiro (é a ordem que se espera de um currículo). Quem
	 * ainda não preencheu data de início fica no fim, sem sumir da lista.
	 */
	@Override
	@Transactional(readOnly = true)
	public List<ExperienceView> getExperiencesOf(String username) {
		var profile = profiles.findByUsername(username == null ? "" : username.trim())
			.orElseThrow(() -> new ApiException(404, "PROFILE_NOT_FOUND", "Perfil não encontrado."));
		return publicExperiences(profile.id());
	}

	/** Same public order, by account id (the professional page has no social @ to look up). */
	@Override
	@Transactional(readOnly = true)
	public List<ExperienceView> publicExperiences(String userId) {
		return repository.findExperiencesByUserId(userId).stream()
			.sorted(java.util.Comparator.comparing(Experience::startDate,
				java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
			.map(this::toExperienceView)
			.toList();
	}

	@Override
	@Transactional
	public ExperienceView addExperience(String userId, String title, String company, String location,
			String description, LocalDate startDate, LocalDate endDate) {
		int ordemAtual = repository.countExperiencesByUserId(userId);
		if (ordemAtual >= MAX_ENTRIES_PER_KIND) {
			throw new ApiException(409, "EXPERIENCES_LIMIT",
				"Você pode ter no máximo " + MAX_ENTRIES_PER_KIND + " experiências no perfil.");
		}
		int order = ordemAtual;
		Experience experience = Experience.create(userId, title, company, location, description, startDate, endDate,
			order, clock.instant());
		return toExperienceView(repository.saveExperience(experience));
	}

	@Override
	@Transactional
	public ExperienceView updateExperience(String userId, String experienceId, String title, String company,
			String location, String description, LocalDate startDate, LocalDate endDate) {
		Experience experience = repository.findExperienceById(experienceId)
			.filter(e -> e.userId().equals(userId))
			.orElseThrow(() -> new ApiException(404, "EXPERIENCE_NOT_FOUND", "Experiencia nao encontrada."));
		experience.update(title, company, location, description, startDate, endDate, clock.instant());
		return toExperienceView(repository.saveExperience(experience));
	}

	@Override
	@Transactional
	public void removeExperience(String userId, String experienceId) {
		repository.findExperienceById(experienceId)
			.filter(e -> e.userId().equals(userId))
			.orElseThrow(() -> new ApiException(404, "EXPERIENCE_NOT_FOUND", "Experiencia nao encontrada."));
		repository.deleteExperience(userId, experienceId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<EducationView> getEducations(String userId) {
		return repository.findEducationsByUserId(userId).stream()
			.map(this::toEducationView)
			.toList();
	}

	@Override
	@Transactional
	public EducationView addEducation(String userId, String school, String degree, String field, String description,
			LocalDate startDate, LocalDate endDate) {
		int ordemAtual = repository.countEducationsByUserId(userId);
		if (ordemAtual >= MAX_ENTRIES_PER_KIND) {
			throw new ApiException(409, "EDUCATION_LIMIT",
				"Você pode ter no máximo " + MAX_ENTRIES_PER_KIND + " formações no perfil.");
		}
		int order = ordemAtual;
		Education education = Education.create(userId, school, degree, field, description, startDate, endDate, order,
			clock.instant());
		return toEducationView(repository.saveEducation(education));
	}

	@Override
	@Transactional
	public EducationView updateEducation(String userId, String educationId, String school, String degree, String field,
			String description, LocalDate startDate, LocalDate endDate) {
		Education education = repository.findEducationById(educationId)
			.filter(e -> e.userId().equals(userId))
			.orElseThrow(() -> new ApiException(404, "EDUCATION_NOT_FOUND", "Formacao nao encontrada."));
		education.update(school, degree, field, description, startDate, endDate, clock.instant());
		return toEducationView(repository.saveEducation(education));
	}

	@Override
	@Transactional
	public void removeEducation(String userId, String educationId) {
		repository.findEducationById(educationId)
			.filter(e -> e.userId().equals(userId))
			.orElseThrow(() -> new ApiException(404, "EDUCATION_NOT_FOUND", "Formacao nao encontrada."));
		repository.deleteEducation(userId, educationId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<CertificationView> getCertifications(String userId) {
		return repository.findCertificationsByUserId(userId).stream()
			.map(this::toCertificationView)
			.toList();
	}

	@Override
	@Transactional
	public CertificationView addCertification(String userId, String name, String issuer, String description,
			LocalDate issueDate, LocalDate expirationDate, String credentialUrl) {
		if (repository.countCertificationsByUserId(userId) >= MAX_ENTRIES_PER_KIND) {
			throw new ApiException(409, "CERTIFICATIONS_LIMIT",
				"Você pode ter no máximo " + MAX_ENTRIES_PER_KIND + " certificações no perfil.");
		}
		Certification certification = Certification.create(userId, name, issuer, description, issueDate, expirationDate,
			com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrl(credentialUrl, "credencial"), clock.instant());
		return toCertificationView(repository.saveCertification(certification));
	}

	@Override
	@Transactional
	public CertificationView updateCertification(String userId, String certificationId, String name, String issuer,
			String description, LocalDate issueDate, LocalDate expirationDate, String credentialUrl) {
		Certification certification = repository.findCertificationById(certificationId)
			.filter(c -> c.userId().equals(userId))
			.orElseThrow(() -> new ApiException(404, "CERTIFICATION_NOT_FOUND", "Certificacao nao encontrada."));
		certification.update(name, issuer, description, issueDate, expirationDate,
			com.socialcoisa.rede.shared.web.SafeUrl.requireHttpUrl(credentialUrl, "credencial"), clock.instant());
		return toCertificationView(repository.saveCertification(certification));
	}

	@Override
	@Transactional
	public void removeCertification(String userId, String certificationId) {
		repository.findCertificationById(certificationId)
			.filter(c -> c.userId().equals(userId))
			.orElseThrow(() -> new ApiException(404, "CERTIFICATION_NOT_FOUND", "Certificacao nao encontrada."));
		repository.deleteCertification(userId, certificationId);
	}

	private ExperienceView toExperienceView(Experience e) {
		return new ExperienceView(e.id(), e.title(), e.company(), e.location(), e.description(), e.currentlyHere(),
			e.startDate(), e.endDate());
	}

	private EducationView toEducationView(Education e) {
		return new EducationView(e.id(), e.school(), e.degree(), e.field(), e.description(), e.currentlyHere(),
			e.startDate(), e.endDate());
	}

	private CertificationView toCertificationView(Certification c) {
		return new CertificationView(c.id(), c.name(), c.issuer(), c.description(), c.issueDate(), c.expirationDate(),
			c.credentialUrl());
	}

}

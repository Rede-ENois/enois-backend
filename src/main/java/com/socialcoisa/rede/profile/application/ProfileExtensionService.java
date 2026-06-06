package com.socialcoisa.rede.profile.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.profile.application.port.in.ManageProfileExtensionUseCase;
import com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository;
import com.socialcoisa.rede.profile.domain.Certification;
import com.socialcoisa.rede.profile.domain.Education;
import com.socialcoisa.rede.profile.domain.Experience;
import com.socialcoisa.rede.shared.web.ApiException;

@Service
public class ProfileExtensionService implements ManageProfileExtensionUseCase {

	private final ProfileExtensionRepository repository;
	private final Clock clock;

	public ProfileExtensionService(ProfileExtensionRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public List<ExperienceView> getExperiences(String userId) {
		return repository.findExperiencesByUserId(userId).stream()
			.map(this::toExperienceView)
			.toList();
	}

	@Override
	@Transactional
	public ExperienceView addExperience(String userId, String title, String company, String location,
			String description, LocalDate startDate, LocalDate endDate) {
		int order = repository.countExperiencesByUserId(userId);
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
		int order = repository.countEducationsByUserId(userId);
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
		Certification certification = Certification.create(userId, name, issuer, description, issueDate, expirationDate,
			credentialUrl, clock.instant());
		return toCertificationView(repository.saveCertification(certification));
	}

	@Override
	@Transactional
	public CertificationView updateCertification(String userId, String certificationId, String name, String issuer,
			String description, LocalDate issueDate, LocalDate expirationDate, String credentialUrl) {
		Certification certification = repository.findCertificationById(certificationId)
			.filter(c -> c.userId().equals(userId))
			.orElseThrow(() -> new ApiException(404, "CERTIFICATION_NOT_FOUND", "Certificacao nao encontrada."));
		certification.update(name, issuer, description, issueDate, expirationDate, credentialUrl, clock.instant());
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

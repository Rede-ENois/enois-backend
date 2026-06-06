package com.socialcoisa.rede.profile.application.port.in;

import java.time.LocalDate;
import java.util.List;

public interface ManageProfileExtensionUseCase {

	List<ExperienceView> getExperiences(String userId);

	ExperienceView addExperience(String userId, String title, String company, String location, String description,
			LocalDate startDate, LocalDate endDate);

	ExperienceView updateExperience(String userId, String experienceId, String title, String company, String location,
			String description, LocalDate startDate, LocalDate endDate);

	void removeExperience(String userId, String experienceId);

	List<EducationView> getEducations(String userId);

	EducationView addEducation(String userId, String school, String degree, String field, String description,
			LocalDate startDate, LocalDate endDate);

	EducationView updateEducation(String userId, String educationId, String school, String degree, String field,
			String description, LocalDate startDate, LocalDate endDate);

	void removeEducation(String userId, String educationId);

	List<CertificationView> getCertifications(String userId);

	CertificationView addCertification(String userId, String name, String issuer, String description,
			LocalDate issueDate, LocalDate expirationDate, String credentialUrl);

	CertificationView updateCertification(String userId, String certificationId, String name, String issuer,
			String description, LocalDate issueDate, LocalDate expirationDate, String credentialUrl);

	void removeCertification(String userId, String certificationId);

	record ExperienceView(String id, String title, String company, String location, String description,
			boolean currentlyHere, LocalDate startDate, LocalDate endDate) {
	}

	record EducationView(String id, String school, String degree, String field, String description,
			boolean currentlyHere, LocalDate startDate, LocalDate endDate) {
	}

	record CertificationView(String id, String name, String issuer, String description, LocalDate issueDate,
			LocalDate expirationDate, String credentialUrl) {
	}

}

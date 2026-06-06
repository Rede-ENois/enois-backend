package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository;
import com.socialcoisa.rede.profile.domain.Certification;
import com.socialcoisa.rede.profile.domain.Education;
import com.socialcoisa.rede.profile.domain.Experience;

@Repository
class ProfileExtensionPersistenceAdapter implements ProfileExtensionRepository {

	private final SpringDataProfileExtensionJpaRepository experienceRepo;
	private final SpringDataEducationJpaRepository educationRepo;
	private final SpringDataCertificationJpaRepository certificationRepo;

	ProfileExtensionPersistenceAdapter(SpringDataProfileExtensionJpaRepository experienceRepo,
			SpringDataEducationJpaRepository educationRepo,
			SpringDataCertificationJpaRepository certificationRepo) {
		this.experienceRepo = experienceRepo;
		this.educationRepo = educationRepo;
		this.certificationRepo = certificationRepo;
	}

	@Override
	public Experience saveExperience(Experience experience) {
		return experienceRepo.save(ExperienceJpaEntity.fromDomain(experience)).toDomain();
	}

	@Override
	public Optional<Experience> findExperienceById(String id) {
		return experienceRepo.findById(id).map(ExperienceJpaEntity::toDomain);
	}

	@Override
	public List<Experience> findExperiencesByUserId(String userId) {
		return experienceRepo.findByUserIdOrderByDisplayOrderAsc(userId).stream()
			.map(ExperienceJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void deleteExperience(String userId, String id) {
		experienceRepo.deleteByUserIdAndId(userId, id);
	}

	@Override
	public Education saveEducation(Education education) {
		return educationRepo.save(EducationJpaEntity.fromDomain(education)).toDomain();
	}

	@Override
	public Optional<Education> findEducationById(String id) {
		return educationRepo.findById(id).map(EducationJpaEntity::toDomain);
	}

	@Override
	public List<Education> findEducationsByUserId(String userId) {
		return educationRepo.findByUserIdOrderByDisplayOrderAsc(userId).stream()
			.map(EducationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void deleteEducation(String userId, String id) {
		educationRepo.deleteByUserIdAndId(userId, id);
	}

	@Override
	public Certification saveCertification(Certification certification) {
		return certificationRepo.save(CertificationJpaEntity.fromDomain(certification)).toDomain();
	}

	@Override
	public Optional<Certification> findCertificationById(String id) {
		return certificationRepo.findById(id).map(CertificationJpaEntity::toDomain);
	}

	@Override
	public List<Certification> findCertificationsByUserId(String userId) {
		return certificationRepo.findByUserIdOrderByIssueDateDesc(userId).stream()
			.map(CertificationJpaEntity::toDomain)
			.toList();
	}

	@Override
	public void deleteCertification(String userId, String id) {
		certificationRepo.deleteByUserIdAndId(userId, id);
	}

	@Override
	public int countExperiencesByUserId(String userId) {
		return (int) experienceRepo.findByUserIdOrderByDisplayOrderAsc(userId).size();
	}

	@Override
	public int countEducationsByUserId(String userId) {
		return (int) educationRepo.findByUserIdOrderByDisplayOrderAsc(userId).size();
	}

	@Override
	public int countCertificationsByUserId(String userId) {
		return (int) certificationRepo.findByUserIdOrderByIssueDateDesc(userId).size();
	}

}

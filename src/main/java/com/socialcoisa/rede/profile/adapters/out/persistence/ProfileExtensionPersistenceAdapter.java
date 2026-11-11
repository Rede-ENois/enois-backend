package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;

import com.socialcoisa.rede.profile.application.port.out.ProfileExtensionRepository;
import com.socialcoisa.rede.profile.domain.Certification;
import com.socialcoisa.rede.profile.domain.Education;
import com.socialcoisa.rede.profile.domain.Experience;

@Repository
class ProfileExtensionPersistenceAdapter implements ProfileExtensionRepository {

	private final SpringDataProfileExtensionJpaRepository experienceRepo;
	private final SpringDataEducationJpaRepository educationRepo;
	private final SpringDataCertificationJpaRepository certificationRepo;
	private final EntityManager em;

	ProfileExtensionPersistenceAdapter(SpringDataProfileExtensionJpaRepository experienceRepo,
			SpringDataEducationJpaRepository educationRepo,
			SpringDataCertificationJpaRepository certificationRepo, EntityManager em) {
		this.experienceRepo = experienceRepo;
		this.educationRepo = educationRepo;
		this.certificationRepo = certificationRepo;
		this.em = em;
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

	@Override
	public Map<String, Integer> experienceCounts(Collection<String> ids) {
		return countsByOwner(ids, "user_experiences");
	}

	@Override
	public Map<String, Integer> educationCounts(Collection<String> ids) {
		return countsByOwner(ids, "user_education");
	}

	@Override
	public Map<String, Integer> certificationCounts(Collection<String> ids) {
		return countsByOwner(ids, "user_certifications");
	}

	/**
	 * Uma consulta por conjunto: o COUNT é feito no banco, não carregando as entidades inteiras
	 * (com descrição e datas) só para chamar `.size()` em memória.
	 */
	private Map<String, Integer> countsByOwner(Collection<String> ids, String table) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = em.createNativeQuery(
				"select user_id, count(*) from " + table + " where user_id in (:ids) group by user_id")
			.setParameter("ids", ids)
			.getResultList();
		var counts = new java.util.HashMap<String, Integer>();
		for (Object[] row : rows) {
			counts.put((String) row[0], ((Number) row[1]).intValue());
		}
		return counts;
	}
}

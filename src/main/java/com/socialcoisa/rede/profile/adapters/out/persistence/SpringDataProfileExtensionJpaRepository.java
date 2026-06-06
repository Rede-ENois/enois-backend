package com.socialcoisa.rede.profile.adapters.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataProfileExtensionJpaRepository extends JpaRepository<ExperienceJpaEntity, String> {

	List<ExperienceJpaEntity> findByUserIdOrderByDisplayOrderAsc(String userId);

	void deleteByUserIdAndId(String userId, String id);

}

interface SpringDataEducationJpaRepository extends JpaRepository<EducationJpaEntity, String> {

	List<EducationJpaEntity> findByUserIdOrderByDisplayOrderAsc(String userId);

	void deleteByUserIdAndId(String userId, String id);

}

interface SpringDataCertificationJpaRepository extends JpaRepository<CertificationJpaEntity, String> {

	List<CertificationJpaEntity> findByUserIdOrderByIssueDateDesc(String userId);

	void deleteByUserIdAndId(String userId, String id);

}

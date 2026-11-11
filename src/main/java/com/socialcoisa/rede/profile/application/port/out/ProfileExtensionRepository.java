package com.socialcoisa.rede.profile.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.profile.domain.Certification;
import com.socialcoisa.rede.profile.domain.Education;
import com.socialcoisa.rede.profile.domain.Experience;

public interface ProfileExtensionRepository {

	Experience saveExperience(Experience experience);

	Optional<Experience> findExperienceById(String id);

	List<Experience> findExperiencesByUserId(String userId);

	void deleteExperience(String userId, String id);

	Education saveEducation(Education education);

	Optional<Education> findEducationById(String id);

	List<Education> findEducationsByUserId(String userId);

	void deleteEducation(String userId, String id);

	Certification saveCertification(Certification certification);

	Optional<Certification> findCertificationById(String id);

	List<Certification> findCertificationsByUserId(String userId);

	void deleteCertification(String userId, String id);

	int countExperiencesByUserId(String userId);

	int countEducationsByUserId(String userId);

	int countCertificationsByUserId(String userId);

	/** Experiências de cada conta do conjunto, em uma consulta (GROUP BY no dono). */
	Map<String, Integer> experienceCounts(Collection<String> ids);

	/** Formações de cada conta do conjunto, em uma consulta. */
	Map<String, Integer> educationCounts(Collection<String> ids);

	/** Certificações de cada conta do conjunto, em uma consulta. */
	Map<String, Integer> certificationCounts(Collection<String> ids);
}

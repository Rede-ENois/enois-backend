package com.socialcoisa.rede.jobs.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.jobs.domain.Job;

public interface JobRepository {

	void save(Job job);

	Optional<Job> findById(String id);

	List<Job> findAll(String query, String type, int limit);

	void deleteById(String id);

	void saveApplication(String jobId, String userId);

	void removeApplication(String jobId, String userId);

	boolean hasApplied(String jobId, String userId);

	void saveBookmark(String jobId, String userId);

	void removeBookmark(String jobId, String userId);

	boolean hasBookmarked(String jobId, String userId);

}

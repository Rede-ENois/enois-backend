package com.socialcoisa.rede.companies.application.port.out;

import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.companies.domain.Company;
import com.socialcoisa.rede.companies.domain.CompanyMember;

public interface CompanyRepository {

	Company save(Company company);

	Optional<Company> findById(String id);

	Optional<Company> findBySlug(String slug);

	/** Pages the user is part of, in any role (the owner is a member too), newest first. */
	List<Company> findByMember(String userId, int limit);

	boolean existsBySlug(String slug);

	List<Company> listAll(int limit);

	CompanyMember saveMember(CompanyMember member);

	Optional<CompanyMember> findMember(String companyId, String userId);

	List<CompanyMember> membersOf(String companyId);

	List<Company> findByIds(java.util.Collection<String> ids);

	/** Active pages whose name contains {@code query} and whose sector is {@code sector} (both optional). */
	List<Company> findActive(String query, String sector, int limit);

	/** Names of every active page (the directory hides job names that already have a page). */
	List<String> activeNames();

	List<String> activeSectors();

	/** Idempotent: following twice keeps one row. */
	void follow(String companyId, String userId, java.time.Instant at);

	/** Idempotent: unfollowing someone who does not follow is a no-op. */
	void unfollow(String companyId, String userId);

	boolean isFollowing(String companyId, String userId);

	long followerCount(String companyId);

	java.util.Map<String, Long> followerCounts(java.util.Collection<String> companyIds);

	List<String> followerIds(String companyId);
}

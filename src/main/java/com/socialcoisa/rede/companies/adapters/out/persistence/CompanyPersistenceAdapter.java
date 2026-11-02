package com.socialcoisa.rede.companies.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import com.socialcoisa.rede.companies.application.port.out.CompanyRepository;
import com.socialcoisa.rede.companies.domain.Company;
import com.socialcoisa.rede.companies.domain.CompanyMember;
import com.socialcoisa.rede.companies.domain.CompanyRole;

@Repository
class CompanyPersistenceAdapter implements CompanyRepository {

	private final SpringDataCompanyJpaRepository companies;
	private final SpringDataCompanyMemberJpaRepository members;
	private final SpringDataCompanyFollowJpaRepository follows;

	CompanyPersistenceAdapter(SpringDataCompanyJpaRepository companies, SpringDataCompanyMemberJpaRepository members,
			SpringDataCompanyFollowJpaRepository follows) {
		this.companies = companies;
		this.members = members;
		this.follows = follows;
	}

	@Override
	public Company save(Company company) {
		return companies.save(CompanyJpaEntity.fromDomain(company)).toDomain();
	}

	@Override
	public Optional<Company> findById(String id) {
		return companies.findById(id).map(CompanyJpaEntity::toDomain);
	}

	@Override
	public Optional<Company> findBySlug(String slug) {
		return companies.findBySlug(slug).map(CompanyJpaEntity::toDomain);
	}

	@Override
	public List<Company> findByMember(String userId, int limit) {
		return companies.findByMember(userId, PageRequest.of(0, Math.max(1, limit)))
			.stream().map(CompanyJpaEntity::toDomain).toList();
	}

	@Override
	public boolean existsBySlug(String slug) {
		return companies.existsBySlug(slug);
	}

	@Override
	public List<Company> listAll(int limit) {
		return companies.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, limit))).stream()
			.map(CompanyJpaEntity::toDomain).toList();
	}

	@Override
	public CompanyMember saveMember(CompanyMember member) {
		return members.save(CompanyMemberJpaEntity.fromDomain(member)).toDomain();
	}

	@Override
	public Optional<CompanyMember> findMember(String companyId, String userId) {
		return members.findByCompanyIdAndUserId(companyId, userId).map(CompanyMemberJpaEntity::toDomain);
	}

	@Override
	public List<CompanyMember> membersOf(String companyId) {
		return members.findByCompanyIdOrderByCreatedAtAsc(companyId).stream()
			.map(CompanyMemberJpaEntity::toDomain).toList();
	}

	@Override
	public List<Company> findByIds(Collection<String> ids) {
		return ids.isEmpty() ? List.of()
			: companies.findAllById(ids).stream().map(CompanyJpaEntity::toDomain).toList();
	}

	@Override
	public List<Company> findActive(String query, String sector, int limit) {
		// `%` and `_` are dropped so the search box text matches as plain text.
		String text = query == null ? "" : query.trim().replace("%", "").replace("_", "").toLowerCase(Locale.ROOT);
		String needle = text.isEmpty() ? null : "%" + text + "%";
		String sectorKey = sector == null || sector.isBlank() ? null : sector.trim().toLowerCase(Locale.ROOT);
		return companies.findActive(needle, sectorKey, PageRequest.of(0, Math.max(1, limit))).stream()
			.map(CompanyJpaEntity::toDomain).toList();
	}

	@Override
	public List<String> activeNames() {
		return companies.activeNames();
	}

	@Override
	public List<String> activeSectors() {
		return companies.activeSectors();
	}

	@Override
	public void follow(String companyId, String userId, Instant at) {
		// ponytail: check-then-insert; two simultaneous clicks can still collide on the primary key
		// (409 for the second), which leaves one row either way.
		if (!follows.existsById(new CompanyFollowJpaEntity.Key(companyId, userId))) {
			follows.save(new CompanyFollowJpaEntity(companyId, userId, at));
		}
	}

	@Override
	public void unfollow(String companyId, String userId) {
		follows.deleteById(new CompanyFollowJpaEntity.Key(companyId, userId));
	}

	@Override
	public boolean isFollowing(String companyId, String userId) {
		return follows.existsById(new CompanyFollowJpaEntity.Key(companyId, userId));
	}

	@Override
	public long followerCount(String companyId) {
		return follows.countByCompanyId(companyId);
	}

	@Override
	public Map<String, Long> followerCounts(Collection<String> companyIds) {
		return companyIds.isEmpty() ? Map.of() : toCounts(follows.countByCompanyIds(companyIds));
	}

	@Override
	public List<String> followerIds(String companyId) {
		return follows.followerIds(companyId);
	}

	static Map<String, Long> toCounts(List<Object[]> rows) {
		Map<String, Long> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put((String) row[0], ((Number) row[1]).longValue());
		}
		return counts;
	}
}

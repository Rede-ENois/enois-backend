package com.socialcoisa.rede.eroleplay.adapters.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequest;
import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequestStatus;
import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignMembership;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.CampaignStatus;
import com.socialcoisa.rede.eroleplay.domain.CampaignVisibility;
import com.socialcoisa.rede.eroleplay.domain.GameTable;
import com.socialcoisa.rede.eroleplay.domain.TableSeat;

@Repository
class EroleplayPersistenceAdapter implements EroleplayRepository {

	/** O que a descoberta mostra: campanha ativa. Arquivada e encerrada nao aparecem. */
	private static final List<CampaignStatus> DISCOVERABLE = List.of(CampaignStatus.ACTIVE);

	/** Complemento de {@link CampaignStatus#readableByMembers()}: o que some da lista de quem participa. */
	private static final List<CampaignStatus> NOT_READABLE_BY_MEMBERS = java.util.Arrays.stream(CampaignStatus.values())
		.filter(status -> !status.readableByMembers())
		.toList();

	private final SpringDataCampaignJpaRepository campaigns;
	private final SpringDataCampaignMemberJpaRepository members;
	private final SpringDataCampaignJoinRequestJpaRepository requests;
	private final SpringDataGameTableJpaRepository tables;
	private final SpringDataTableSeatJpaRepository seats;

	EroleplayPersistenceAdapter(SpringDataCampaignJpaRepository campaigns,
			SpringDataCampaignMemberJpaRepository members,
			SpringDataCampaignJoinRequestJpaRepository requests, SpringDataGameTableJpaRepository tables,
			SpringDataTableSeatJpaRepository seats) {
		this.campaigns = campaigns;
		this.members = members;
		this.requests = requests;
		this.tables = tables;
		this.seats = seats;
	}

	@Override
	public void saveCampaign(Campaign campaign) {
		campaigns.save(CampaignJpaEntity.fromDomain(campaign));
	}

	@Override
	public Optional<Campaign> findCampaignById(String id) {
		return campaigns.findById(id).map(CampaignJpaEntity::toDomain);
	}

	@Override
	public Optional<Campaign> findCampaignBySlug(String slug) {
		return campaigns.findBySlug(slug).map(CampaignJpaEntity::toDomain);
	}

	@Override
	public boolean existsCampaignBySlug(String slug) {
		return campaigns.existsBySlug(slug);
	}

	@Override
	public List<Campaign> listVisibleCampaigns(String query, Instant before, int limit) {
		String like = com.socialcoisa.rede.shared.web.LikePattern.contains(query);
		return campaigns.listVisible(DISCOVERABLE, CampaignVisibility.PUBLIC, like, before, PageRequest.of(0, limit))
			.stream()
			.map(CampaignJpaEntity::toDomain)
			.toList();
	}

	@Override
	public List<CampaignMembership> findMemberships(String userId, int limit) {
		return members.findMemberships(userId, NOT_READABLE_BY_MEMBERS, PageRequest.of(0, limit)).stream()
			.map(row -> new CampaignMembership(((CampaignJpaEntity) row[1]).toDomain(),
				((CampaignMemberJpaEntity) row[0]).role()))
			.toList();
	}

	@Override
	public Map<String, Long> countMembers(Collection<String> campaignIds) {
		if (campaignIds.isEmpty()) {
			return Map.of();
		}
		return members.countByCampaignIds(campaignIds).stream()
			.collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).longValue()));
	}

	@Override
	public void saveMember(CampaignMember member) {
		members.save(CampaignMemberJpaEntity.fromDomain(member));
	}

	@Override
	public Optional<CampaignMember> findMember(String campaignId, String userId) {
		return members.findByCampaignIdAndUserId(campaignId, userId).map(CampaignMemberJpaEntity::toDomain);
	}

	@Override
	public List<CampaignMember> listMembers(String campaignId) {
		return members.findByCampaignId(campaignId).stream().map(CampaignMemberJpaEntity::toDomain).toList();
	}

	@Override
	public long countMembers(String campaignId) {
		return members.countByCampaignId(campaignId);
	}

	@Override
	public void removeMember(String campaignId, String userId) {
		members.deleteById(campaignId + ":" + userId);
	}

	@Override
	public void adjustMemberCount(String campaignId, int delta) {
		campaigns.adjustMemberCount(campaignId, delta);
	}

	@Override
	public void saveJoinRequest(CampaignJoinRequest request) {
		requests.save(CampaignJoinRequestJpaEntity.fromDomain(request));
	}

	@Override
	public Optional<CampaignJoinRequest> findPendingRequest(String campaignId, String userId) {
		return requests.findByCampaignIdAndUserIdAndStatus(campaignId, userId, CampaignJoinRequestStatus.PENDING)
			.map(CampaignJoinRequestJpaEntity::toDomain);
	}

	@Override
	public Optional<CampaignJoinRequest> findRequestById(String id) {
		return requests.findById(id).map(CampaignJoinRequestJpaEntity::toDomain);
	}

	@Override
	public List<CampaignJoinRequest> listPendingRequests(String campaignId) {
		return requests.findByCampaignIdAndStatus(campaignId, CampaignJoinRequestStatus.PENDING).stream()
			.map(CampaignJoinRequestJpaEntity::toDomain)
			.toList();
	}

	@Override
	public int markRequestDecided(String requestId, CampaignJoinRequestStatus status, String actorId, Instant now) {
		return requests.markDecided(requestId, status, actorId, now, CampaignJoinRequestStatus.PENDING);
	}

	@Override
	public void saveTable(GameTable table) {
		tables.save(GameTableJpaEntity.fromDomain(table));
	}

	@Override
	public Optional<GameTable> findTableById(String id) {
		return tables.findById(id).map(GameTableJpaEntity::toDomain);
	}

	@Override
	public List<GameTable> listTables(String campaignId) {
		return tables.findByCampaignIdOrderByCreatedAtDesc(campaignId).stream().map(GameTableJpaEntity::toDomain).toList();
	}

	@Override
	public void saveSeat(TableSeat seat) {
		seats.save(TableSeatJpaEntity.fromDomain(seat));
	}

	@Override
	public Optional<TableSeat> findSeat(String tableId, String userId) {
		return seats.findByTableIdAndUserId(tableId, userId).map(TableSeatJpaEntity::toDomain);
	}

	@Override
	public List<TableSeat> listSeats(String tableId) {
		return seats.findByTableIdOrderBySeatedAtAsc(tableId).stream().map(TableSeatJpaEntity::toDomain).toList();
	}

	@Override
	public void removeSeat(String tableId, String userId) {
		seats.deleteById(tableId + ":" + userId);
	}
}

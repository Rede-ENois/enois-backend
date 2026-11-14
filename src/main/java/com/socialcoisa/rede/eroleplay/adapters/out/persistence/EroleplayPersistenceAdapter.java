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
import com.socialcoisa.rede.eroleplay.domain.CharacterSheet;
import com.socialcoisa.rede.eroleplay.domain.DiceRoll;
import com.socialcoisa.rede.eroleplay.domain.GameTable;
import com.socialcoisa.rede.eroleplay.domain.RollRequest;
import com.socialcoisa.rede.eroleplay.domain.RollRequestStatus;
import com.socialcoisa.rede.eroleplay.domain.SheetEntry;
import com.socialcoisa.rede.eroleplay.domain.SheetField;
import com.socialcoisa.rede.eroleplay.domain.SheetTemplate;
import com.socialcoisa.rede.eroleplay.domain.SheetVisibility;
import com.socialcoisa.rede.eroleplay.domain.TableSeat;

import tools.jackson.databind.ObjectMapper;

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
	private final SpringDataSheetTemplateJpaRepository templates;
	private final SpringDataCharacterSheetJpaRepository sheets;
	private final SpringDataDiceRollJpaRepository rolls;
	private final SpringDataRollRequestJpaRepository rollRequests;
	private final ObjectMapper objectMapper;

	EroleplayPersistenceAdapter(SpringDataCampaignJpaRepository campaigns,
			SpringDataCampaignMemberJpaRepository members,
			SpringDataCampaignJoinRequestJpaRepository requests, SpringDataGameTableJpaRepository tables,
			SpringDataTableSeatJpaRepository seats, SpringDataSheetTemplateJpaRepository templates,
			SpringDataCharacterSheetJpaRepository sheets, SpringDataDiceRollJpaRepository rolls,
			SpringDataRollRequestJpaRepository rollRequests, ObjectMapper objectMapper) {
		this.campaigns = campaigns;
		this.members = members;
		this.requests = requests;
		this.tables = tables;
		this.seats = seats;
		this.templates = templates;
		this.sheets = sheets;
		this.rolls = rolls;
		this.rollRequests = rollRequests;
		this.objectMapper = objectMapper;
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

	@Override
	public void saveSheetTemplate(SheetTemplate template) {
		String fieldsJson = objectMapper.writeValueAsString(template.fields());
		SheetTemplateJpaEntity existing = templates.findByCampaignId(template.campaignId()).orElse(null);
		String id = existing == null ? template.id() : existing.id();
		templates.save(new SheetTemplateJpaEntity(id, template.campaignId(), template.gameSystem(), fieldsJson,
			existing == null ? template.createdAt() : existing.createdAt(), template.updatedAt()));
	}

	@Override
	public Optional<SheetTemplate> findSheetTemplate(String campaignId) {
		return templates.findByCampaignId(campaignId).map(this::toTemplate);
	}

	@Override
	public boolean existsSheetTemplate(String campaignId) {
		return templates.existsByCampaignId(campaignId);
	}

	@Override
	public void deleteSheetTemplate(String campaignId) {
		templates.findByCampaignId(campaignId).ifPresent(entity -> templates.deleteById(entity.id()));
	}

	@Override
	public void saveSheet(CharacterSheet sheet) {
		String entriesJson = objectMapper.writeValueAsString(sheet.all());
		CharacterSheetJpaEntity existing = sheets.findByCampaignIdAndUserId(sheet.campaignId(), sheet.userId())
			.orElse(null);
		String id = existing == null ? sheet.id() : existing.id();
		sheets.save(new CharacterSheetJpaEntity(id, sheet.campaignId(), sheet.userId(), sheet.name(), entriesJson,
			existing == null ? sheet.createdAt() : existing.createdAt(), sheet.updatedAt()));
	}

	@Override
	public Optional<CharacterSheet> findSheet(String campaignId, String userId) {
		return sheets.findByCampaignIdAndUserId(campaignId, userId).map(this::toSheet);
	}

	@Override
	public List<CharacterSheet> listSheets(String campaignId) {
		return sheets.findByCampaignIdOrderByUpdatedAtDesc(campaignId).stream().map(this::toSheet).toList();
	}

	@Override
	public long countSheets(String campaignId) {
		return sheets.countByCampaignId(campaignId);
	}

	@Override
	public void saveRoll(DiceRoll roll) {
		String diceJson = objectMapper.writeValueAsString(roll.dice());
		rolls.save(new DiceRollJpaEntity(roll.id(), roll.campaignId(), roll.rollerId(), roll.expression(),
			diceJson, roll.total(), roll.label(), roll.visibility().name(), roll.requestId(), roll.createdAt()));
	}

	@Override
	public List<DiceRoll> listRolls(String campaignId, int limit) {
		return rolls.findByCampaignIdOrderByCreatedAtDesc(campaignId, PageRequest.of(0, limit))
			.stream().map(this::toRoll).toList();
	}

	@Override
	public Optional<DiceRoll> findRoll(String campaignId, String rollId) {
		return rolls.findById(rollId)
			.filter(entity -> entity.campaignId().equals(campaignId))
			.map(this::toRoll);
	}

	@Override
	public void saveRollRequest(RollRequest request) {
		RollRequestJpaEntity existing = rollRequests.findByCampaignIdAndId(request.campaignId(), request.id())
			.orElse(null);
		Instant answeredAt = existing == null ? request.answeredAt() : existing.answeredAt();
		rollRequests.save(new RollRequestJpaEntity(request.id(), request.campaignId(), request.masterId(),
			request.playerId(), request.expression(), request.label(), request.maxVisibility().name(),
			request.reason(), request.status().name(), request.answeredRollId(), request.createdAt(),
			request.status() == RollRequestStatus.ANSWERED ? request.answeredAt() : answeredAt));
	}

	@Override
	public Optional<RollRequest> findRollRequest(String campaignId, String requestId) {
		return rollRequests.findByCampaignIdAndId(campaignId, requestId).map(this::toRollRequest);
	}

	@Override
	public List<RollRequest> listRollRequests(String campaignId) {
		return rollRequests.findByCampaignIdOrderByCreatedAtDesc(campaignId).stream()
			.map(this::toRollRequest).toList();
	}

	@Override
	public List<RollRequest> listRollRequestsOf(String campaignId, String playerId) {
		return rollRequests.findByCampaignIdAndPlayerIdOrderByCreatedAtDesc(campaignId, playerId).stream()
			.map(this::toRollRequest).toList();
	}

	private DiceRoll toRoll(DiceRollJpaEntity entity) {
		List<Integer> dice = List.of(objectMapper.readValue(entity.diceJson(), Integer[].class));
		return DiceRoll.restore(entity.id(), entity.campaignId(), entity.rollerId(), entity.expression(),
			dice, entity.total(), entity.label(), SheetVisibility.of(entity.visibility()), entity.requestId(),
			entity.createdAt());
	}

	private RollRequest toRollRequest(RollRequestJpaEntity entity) {
		return RollRequest.restore(entity.id(), entity.campaignId(), entity.masterId(), entity.playerId(),
			entity.expression(), entity.label(), SheetVisibility.of(entity.maxVisibility()), entity.reason(),
			RollRequestStatus.valueOf(entity.status()), entity.answeredRollId(), entity.createdAt(),
			entity.answeredAt());
	}

	private SheetTemplate toTemplate(SheetTemplateJpaEntity entity) {
		List<SheetField> fields = List.of(objectMapper.readValue(entity.fieldsJson(), SheetField[].class));
		return SheetTemplate.restore(entity.id(), entity.campaignId(), entity.gameSystem(), fields,
			entity.createdAt(), entity.updatedAt());
	}

	private CharacterSheet toSheet(CharacterSheetJpaEntity entity) {
		List<SheetEntry> entries = List.of(objectMapper.readValue(entity.entriesJson(), SheetEntry[].class));
		return CharacterSheet.restore(entity.id(), entity.campaignId(), entity.userId(), entity.name(), entries,
			entity.createdAt(), entity.updatedAt());
	}
}

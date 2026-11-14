package com.socialcoisa.rede.eroleplay.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignCapability;
import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequest;
import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequestStatus;
import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignMembership;
import com.socialcoisa.rede.eroleplay.domain.CampaignPermissions;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.CampaignSlug;
import com.socialcoisa.rede.eroleplay.domain.CampaignStatus;
import com.socialcoisa.rede.eroleplay.domain.CampaignVisibility;
import com.socialcoisa.rede.eroleplay.domain.GameSystemFamily;
import com.socialcoisa.rede.eroleplay.domain.GameTable;
import com.socialcoisa.rede.eroleplay.domain.JoinOutcome;
import com.socialcoisa.rede.eroleplay.domain.SystemAdapter;
import com.socialcoisa.rede.eroleplay.domain.TableSeat;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O ciclo de vida da campanha e da mesa: criar, editar, convidar, entrar, sair, sentar.
 *
 * <p>A autorização não é comparada aqui: cada operação pergunta por uma {@link CampaignCapability}
 * em {@link CampaignPermissions}. Assim a régua mora num lugar só, e a tela não é a autoridade.
 */
@Service
public class CampaignService {

	private static final int NAME_MAX = 120;
	private static final int SLUG_MAX = 80;
	private static final int MAX_PAGE = 50;

	private final EroleplayRepository repository;
	private final CampaignNotifier notifier;
	private final GameSystemRegistry systems;
	private final Clock clock;

	public CampaignService(EroleplayRepository repository, CampaignNotifier notifier, GameSystemRegistry systems,
			Clock clock) {
		this.repository = repository;
		this.notifier = notifier;
		this.systems = systems;
		this.clock = clock;
	}

	/**
	 * Cria a campanha. Quem cria é o mestre (GAME_MASTER) e entra como participante na hora.
	 *
	 * <p>O sistema de regras é escolhido na criação e gravado como identificador: a família é o
	 * que o motor genérico entende (D20, D100, autoral) e o identificador aponta para o adapter
	 * concreto no Registry. Guardar o adapter inteiro na campanha seria duplicar o sistema.</p>
	 */
	@Transactional
	public Campaign create(String name, String description, String setting, String gameSystem,
			GameSystemFamily systemFamily, CampaignVisibility visibility, String ownerId) {
		if (!StringUtils.hasText(name)) {
			throw new ApiException(400, "INVALID_NAME", "Nome da campanha e obrigatorio.");
		}
		String finalName = name.trim();
		if (finalName.length() > NAME_MAX) {
			throw new ApiException(400, "INVALID_NAME", "Nome da campanha deve ter ate 120 caracteres.");
		}
		SystemAdapter system = systems.require(gameSystem);
		GameSystemFamily family = familyOf(system, systemFamily);
		Instant now = clock.instant();
		Campaign campaign = Campaign.create(newId("roleplay_campaign"), uniqueSlug(CampaignSlug.from(finalName)),
			finalName, trimToNull(description), trimToNull(setting), gameSystem.trim(), family,
			visibility == null ? CampaignVisibility.PRIVATE : visibility, ownerId, now);
		repository.saveCampaign(campaign);
		repository.saveMember(CampaignMember.join(campaign.id(), ownerId, CampaignRole.GAME_MASTER, now));
		repository.adjustMemberCount(campaign.id(), 1);
		return campaign;
	}

	/** Edita a campanha. Quem edita é o mestre — a tela nunca decide quem administra. */
	@Transactional
	public Campaign update(String campaignId, String name, String description, String setting, String gameSystem,
			GameSystemFamily systemFamily, CampaignVisibility visibility, String actorId) {
		Campaign campaign = requireCampaign(campaignId);
		require(roleOf(campaignId, actorId), CampaignCapability.MANAGE_CAMPAIGN);
		String finalName = name == null ? null : name.trim();
		if (finalName != null && finalName.isEmpty()) {
			throw new ApiException(400, "INVALID_NAME", "Nome da campanha e obrigatorio.");
		}
		if (finalName != null && finalName.length() > NAME_MAX) {
			throw new ApiException(400, "INVALID_NAME", "Nome da campanha deve ter ate 120 caracteres.");
		}
		String nextSystem = null;
		GameSystemFamily nextFamily = systemFamily;
		if (gameSystem != null) {
			SystemAdapter system = systems.require(gameSystem);
			nextSystem = system.id();
			nextFamily = familyOf(system, systemFamily);
			if (!system.id().equals(campaign.gameSystem())
					&& (repository.existsSheetTemplate(campaignId) || repository.countSheets(campaignId) > 0)) {
				throw new ApiException(409, "SHEET_SYSTEM_LOCKED",
					"A campanha ja tem ficha montada. Remova o modelo antes de trocar o sistema de regras.");
			}
		}
		Campaign updated = campaign.update(finalName, trimToNull(description), trimToNull(setting), nextSystem,
			nextFamily, visibility, clock.instant());
		repository.saveCampaign(updated);
		return updated;
	}

	/** Público: a página da campanha abre pelo slug; ações internas usam o id. */
	@Transactional(readOnly = true)
	public Campaign resolve(String slugOrId) {
		return repository.findCampaignBySlug(slugOrId)
			.or(() -> repository.findCampaignById(slugOrId))
			.orElseThrow(() -> new ApiException(404, "CAMPAIGN_NOT_FOUND", "Campanha nao encontrada."));
	}

	@Transactional(readOnly = true)
	public List<Campaign> list(String query, Instant before, int limit) {
		return repository.listVisibleCampaigns(StringUtils.hasText(query) ? query.trim() : null, before,
			Math.min(Math.max(limit, 1), MAX_PAGE));
	}

	@Transactional(readOnly = true)
	public List<CampaignMembership> listMine(String userId, int limit) {
		return repository.findMemberships(userId, Math.min(Math.max(limit, 1), MAX_PAGE));
	}

	/** Papel de alguém na campanha; nulo = não participa. */
	@Transactional(readOnly = true)
	public CampaignRole roleOf(String campaignId, String userId) {
		return repository.findMember(campaignId, userId).map(CampaignMember::role).orElse(null);
	}

	@Transactional(readOnly = true)
	public List<CampaignMember> listMembers(String campaignId, String actorId) {
		require(roleOf(campaignId, actorId), CampaignCapability.VIEW_CAMPAIGN);
		return repository.listMembers(campaignId);
	}

	/**
	 * Entra na campanha.
	 *
	 * <p>Campanha PÚBLICA entra na hora; INVITE_ONLY deixa o pedido pendente até uma decisão do
	 * mestre; PRIVATE não tem entrada — só o convite do mestre cria a associação.</p>
	 */
	@Transactional
	public JoinOutcome join(String campaignId, String userId) {
		Campaign campaign = requireCampaign(campaignId);
		if (!campaign.status().acceptsNewMembers()) {
			throw new ApiException(409, "CAMPAIGN_NOT_ACCEPTING_MEMBERS",
				"Esta campanha nao esta aceitando novos participantes.");
		}
		if (repository.findMember(campaignId, userId).isPresent()) {
			throw new ApiException(409, "ALREADY_MEMBER", "Voce ja participa desta campanha.");
		}
		if (campaign.visibility() == CampaignVisibility.PRIVATE) {
			throw new ApiException(403, "CAMPAIGN_INVITE_ONLY",
				"Esta campanha e por convite. Peça ao mestre para te chamar.");
		}
		if (campaign.visibility() == CampaignVisibility.INVITE_ONLY) {
			if (repository.findPendingRequest(campaignId, userId).isPresent()) {
				throw new ApiException(409, "REQUEST_ALREADY_PENDING", "Seu pedido ja esta pendente.");
			}
			repository.saveJoinRequest(CampaignJoinRequest.create(newId("roleplay_request"), campaignId, userId,
				null, clock.instant()));
			notifier.joinRequested(campaign, userId);
			return JoinOutcome.PENDING;
		}
		repository.saveMember(CampaignMember.join(campaignId, userId, CampaignRole.PLAYER, clock.instant()));
		repository.adjustMemberCount(campaignId, 1);
		notifier.memberJoined(campaign, userId);
		return JoinOutcome.MEMBER;
	}

	/** O mestre convida: a associação nasce na hora, sem passar por pedido. */
	@Transactional
	public void invite(String campaignId, String actorId, String targetUserId) {
		require(roleOf(campaignId, actorId), CampaignCapability.MANAGE_MEMBERS);
		Campaign campaign = requireCampaign(campaignId);
		if (repository.findMember(campaignId, targetUserId).isPresent()) {
			throw new ApiException(409, "ALREADY_MEMBER", "Esta pessoa ja participa da campanha.");
		}
		repository.saveMember(CampaignMember.join(campaignId, targetUserId, CampaignRole.PLAYER, clock.instant()));
		repository.adjustMemberCount(campaignId, 1);
		notifier.memberInvited(campaign, targetUserId, actorId);
	}

	/** Sai da campanha. O mestre precisa transferir o comando antes — uma campanha sem mestre não existe. */
	@Transactional
	public void leave(String campaignId, String userId) {
		CampaignMember member = repository.findMember(campaignId, userId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Voce nao participa desta campanha."));
		if (member.role() == CampaignRole.GAME_MASTER) {
			throw new ApiException(409, "MASTERSHIP_TRANSFER_REQUIRED",
				"Transfira o comando da campanha para outro participante antes de sair.");
		}
		repository.removeMember(campaignId, userId);
		repository.adjustMemberCount(campaignId, -1);
	}

	/** O mestre remove um participante. */
	@Transactional
	public void removeMember(String campaignId, String actorId, String targetUserId) {
		require(roleOf(campaignId, actorId), CampaignCapability.MANAGE_MEMBERS);
		CampaignMember target = repository.findMember(campaignId, targetUserId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Esta pessoa nao participa da campanha."));
		if (target.role() == CampaignRole.GAME_MASTER) {
			throw new ApiException(409, "CANNOT_REMOVE_MASTER", "O mestre da campanha nao pode ser removido.");
		}
		repository.removeMember(campaignId, targetUserId);
		repository.adjustMemberCount(campaignId, -1);
	}

	/** Passa o comando da campanha para outro participante. O antigo mestre continua como jogador. */
	@Transactional
	public void transferMaster(String campaignId, String actorId, String targetUserId) {
		Campaign campaign = requireCampaign(campaignId);
		require(roleOf(campaignId, actorId), CampaignCapability.TRANSFER_MASTERSHIP);
		CampaignMember target = repository.findMember(campaignId, targetUserId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Esta pessoa nao participa da campanha."));
		if (target.userId().equals(campaign.ownerId())) {
			throw new ApiException(409, "ALREADY_MASTER", "Esta pessoa ja e a mestre da campanha.");
		}
		CampaignMember current = repository.findMember(campaignId, actorId)
			.orElseThrow(() -> new ApiException(404, "NOT_MEMBER", "Voce nao participa desta campanha."));
		repository.saveCampaign(campaign.withOwner(targetUserId, clock.instant()));
		repository.saveMember(target.withRole(CampaignRole.GAME_MASTER));
		repository.saveMember(current.withRole(CampaignRole.PLAYER));
		notifier.masterTransferred(campaign, targetUserId, actorId);
	}

	@Transactional(readOnly = true)
	public List<CampaignJoinRequest> listPendingRequests(String campaignId, String actorId) {
		require(roleOf(campaignId, actorId), CampaignCapability.MANAGE_MEMBERS);
		return repository.listPendingRequests(campaignId);
	}

	/**
	 * Aprova ou rejeita um pedido.
	 *
	 * <p>A decisão é atômica (só muda o que ainda está PENDENTE): duas aprovações simultâneas não
	 * viram duas associações — a segunda recebe 409.</p>
	 */
	@Transactional
	public CampaignJoinRequest decideRequest(String campaignId, String requestId, String actorId, boolean approve) {
		require(roleOf(campaignId, actorId), CampaignCapability.MANAGE_MEMBERS);
		Campaign campaign = requireCampaign(campaignId);
		CampaignJoinRequest request = repository.findRequestById(requestId)
			.filter(found -> found.campaignId().equals(campaignId))
			.orElseThrow(() -> new ApiException(404, "REQUEST_NOT_FOUND", "Solicitacao nao encontrada."));
		Instant now = clock.instant();
		CampaignJoinRequestStatus decided = approve ? CampaignJoinRequestStatus.APPROVED
			: CampaignJoinRequestStatus.REJECTED;
		int changed = repository.markRequestDecided(requestId, decided, actorId, now);
		if (changed == 0) {
			throw new ApiException(409, "REQUEST_ALREADY_DECIDED", "Esta solicitacao ja foi decidida.");
		}
		if (approve && repository.findMember(campaignId, request.userId()).isEmpty()) {
			repository.saveMember(CampaignMember.join(campaignId, request.userId(), CampaignRole.PLAYER, now));
			repository.adjustMemberCount(campaignId, 1);
		}
		notifier.requestDecided(campaign, request.userId(), actorId, approve);
		return repository.findRequestById(requestId).orElse(request);
	}

	/** Arquiva a campanha: sai da descoberta, o conteúdo continua legível para quem participa. */
	@Transactional
	public Campaign archive(String campaignId, String actorId) {
		Campaign campaign = requireCampaign(campaignId);
		require(roleOf(campaignId, actorId), CampaignCapability.MANAGE_CAMPAIGN);
		Campaign archived = campaign.withStatus(CampaignStatus.ARCHIVED, clock.instant());
		repository.saveCampaign(archived);
		return archived;
	}

	/** Cria uma mesa dentro da campanha. Só o mestre cria mesa. */
	@Transactional
	public GameTable createTable(String campaignId, String name, String actorId, boolean open) {
		Campaign campaign = requireCampaign(campaignId);
		require(roleOf(campaignId, actorId), CampaignCapability.MANAGE_TABLES);
		if (!StringUtils.hasText(name)) {
			throw new ApiException(400, "INVALID_NAME", "Nome da mesa e obrigatorio.");
		}
		String finalName = name.trim();
		if (finalName.length() > NAME_MAX) {
			throw new ApiException(400, "INVALID_NAME", "Nome da mesa deve ter ate 120 caracteres.");
		}
		Instant now = clock.instant();
		GameTable table = GameTable.create(newId("roleplay_table"), campaign.id(), finalName, actorId, open, now);
		repository.saveTable(table);
		// O criador já senta: uma mesa sem ninguém não é mesa.
		repository.saveSeat(TableSeat.seat(table.id(), actorId, now));
		return table;
	}

	@Transactional(readOnly = true)
	public List<GameTable> listTables(String campaignId, String actorId) {
		require(roleOf(campaignId, actorId), CampaignCapability.VIEW_CAMPAIGN);
		return repository.listTables(campaignId);
	}

	/**
	 * Senta na mesa.
	 *
	 * <p>Mesa aberta: quem participa da campanha senta direto. Mesa fechada: só o dono senta — e o
	 * pedido de entrada na mesa fica para a fase de sessões (Fase 8), quando a mesa tem estado
	 * próprio para guardar.</p>
	 */
	@Transactional
	public void sit(String tableId, String userId) {
		GameTable table = requireTable(tableId);
		// Sentar exige participar da campanha: a mesa vive dentro dela.
		CampaignRole role = roleOf(table.campaignId(), userId);
		if (role == null) {
			throw new ApiException(403, "NOT_CAMPAIGN_MEMBER", "Voce nao participa desta campanha.");
		}
		if (repository.findSeat(tableId, userId).isPresent()) {
			throw new ApiException(409, "ALREADY_SEATED", "Voce ja esta sentado nesta mesa.");
		}
		if (!table.isOpen() && !table.ownerId().equals(userId)) {
			throw new ApiException(403, "TABLE_NOT_OPEN", "Esta mesa esta fechada para novos jogadores.");
		}
		repository.saveSeat(TableSeat.seat(tableId, userId, clock.instant()));
	}

	@Transactional
	public void leaveTable(String tableId, String userId) {
		requireTable(tableId);
		if (repository.findSeat(tableId, userId).isEmpty()) {
			throw new ApiException(404, "NOT_SEATED", "Voce nao esta sentado nesta mesa.");
		}
		repository.removeSeat(tableId, userId);
	}

	@Transactional(readOnly = true)
	public List<TableSeat> listSeats(String tableId, String actorId) {
		GameTable table = requireTable(tableId);
		require(roleOf(table.campaignId(), actorId), CampaignCapability.VIEW_CAMPAIGN);
		return repository.listSeats(tableId);
	}

	private Campaign requireCampaign(String campaignId) {
		return repository.findCampaignById(campaignId)
			.orElseThrow(() -> new ApiException(404, "CAMPAIGN_NOT_FOUND", "Campanha nao encontrada."));
	}

	private GameTable requireTable(String tableId) {
		return repository.findTableById(tableId)
			.orElseThrow(() -> new ApiException(404, "TABLE_NOT_FOUND", "Mesa nao encontrada."));
	}

	/**
	 * Confere uma capacidade e devolve o papel de quem pediu — a porta que os serviços vizinhos usam.
	 *
	 * <p>Existe para que a régua continue num lugar só: o serviço de ficha não compara papel, ele
	 * pede a capacidade. Devolver o papel evita uma segunda consulta para saber se a pessoa é
	 * mestre, o que abriria espaço para as duas respostas divergirem.</p>
	 */
	@Transactional(readOnly = true)
	public CampaignRole requirePermission(String campaignId, String userId, CampaignCapability capability) {
		requireCampaign(campaignId);
		CampaignRole role = roleOf(campaignId, userId);
		require(role, capability);
		return role;
	}

	/**
	 * Verifica uma capacidade na frente de quem vai usá-la fora daqui (o chat e os próximos
	 * recursos de campanha perguntam pela matriz em vez de reimplementar a régua).
	 */
	@Transactional(readOnly = true)
	public void requireCapability(String campaignId, String userId, CampaignCapability capability) {
		require(roleOf(campaignId, userId), capability);
	}

	private void require(CampaignRole role, CampaignCapability capability) {
		if (!CampaignPermissions.can(role, capability)) {
			throw new ApiException(403, "FORBIDDEN", "Voce nao tem permissao para isso nesta campanha.");
		}
	}

	private String uniqueSlug(String slug) {
		if (!CampaignSlug.isReserved(slug) && !repository.existsCampaignBySlug(slug)) {
			return slug;
		}
		// O sufixo precisa caber no teto da coluna: encurta a base antes de numerar.
		String base = slug.length() > SLUG_MAX - 4 ? slug.substring(0, SLUG_MAX - 4).replaceAll("-+$", "") : slug;
		for (int n = 2; n < 1000; n++) {
			String candidate = base + "-" + n;
			if (!repository.existsCampaignBySlug(candidate)) {
				return candidate;
			}
		}
		throw new ApiException(409, "SLUG_UNAVAILABLE", "Nao foi possivel gerar um endereco para a campanha.");
	}

	/**
	 * A familia vem do adapter, nao do pedido: o cliente nao escolhe dizer que um sistema D100 e D20.
	 * Quando o corpo informa uma familia, ela precisa bater com a do sistema registrado.
	 */
	private GameSystemFamily familyOf(SystemAdapter system, GameSystemFamily declared) {
		if (declared != null && declared != system.family()) {
			throw new ApiException(400, "GAME_SYSTEM_FAMILY_MISMATCH",
				"A familia informada nao corresponde ao sistema escolhido.");
		}
		return system.family();
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}

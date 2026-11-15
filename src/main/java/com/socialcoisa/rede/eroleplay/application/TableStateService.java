package com.socialcoisa.rede.eroleplay.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.eroleplay.application.CampaignChatChanged;
import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.CampaignCapability;
import com.socialcoisa.rede.eroleplay.domain.CampaignPermissions;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.GameTable;
import com.socialcoisa.rede.eroleplay.domain.TableState;
import com.socialcoisa.rede.eroleplay.domain.TableToken;
import com.socialcoisa.rede.shared.web.ApiException;

import org.springframework.context.ApplicationEventPublisher;

/**
 * O estado vivo da mesa: ordem de fala, vez, cena e tokens — e a porta para a voz do Épapo.
 *
 * <p>A autorização mora na matriz de capacidades como em todo o Éroleplay: mudar a mesa é do
 * mestre ({@link CampaignCapability#MANAGE_TABLES}); ver é de quem participa
 * ({@link CampaignCapability#VIEW_CAMPAIGN}). Toda mudança publica o mesmo sinal do chat
 * ({@link CampaignChatChanged}) para os participantes redesenharem a mesa.</p>
 */
@Service
public class TableStateService {

	private final EroleplayRepository repository;
	private final CampaignService campaigns;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public TableStateService(EroleplayRepository repository, CampaignService campaigns,
			ApplicationEventPublisher events, Clock clock) {
		this.repository = repository;
		this.campaigns = campaigns;
		this.events = events;
		this.clock = clock;
	}

	// ------------------------------------------------------------------ estado (turno e cena)

	/** O estado da mesa; nasce vazio na primeira leitura — mesa sem estado é mesa não começada. */
	@Transactional
	public TableState state(String tableId, String actorId) {
		GameTable table = requireTable(tableId);
		validateViewer(table.campaignId(), actorId);
		return repository.findTableState(tableId).orElseGet(() -> {
			TableState created = TableState.empty(tableId, table.campaignId(), clock.instant());
			return repository.saveTableState(created);
		});
	}

	/** Grava a ordem de fala. Só o mestre escreve na mesa. */
	@Transactional
	public TableState setTurnOrder(String tableId, String actorId, List<String> order) {
		GameState game = stateForWrite(tableId, actorId);
		return announce(game.state().withTurnOrder(order, clock.instant()), game.table());
	}

	/** Passa a vez. O mestre passa para qualquer um; jogador passa para si mesmo quando chega a vez. */
	@Transactional
	public TableState setTurn(String tableId, String actorId, String targetUserId) {
		GameTable table = requireTable(tableId);
		validateViewer(table.campaignId(), actorId);
		String target = targetUserId == null ? actorId : targetUserId;
		if (!target.equals(actorId)) {
			// Passar a vez para outra pessoa é escolha do mestre; jogador entrega só a vez dele.
			validateMaster(table.campaignId(), actorId);
		}
		TableState current = loadState(table);
		return announce(current.withTurn(target, clock.instant()), table);
	}

	/** Encerra a vez sem próximo definido. O mestre limpa qualquer; jogador limpa a vez dele. */
	@Transactional
	public TableState clearTurn(String tableId, String actorId) {
		GameTable table = requireTable(tableId);
		validateViewer(table.campaignId(), actorId);
		TableState current = loadState(table);
		String active = current.activeTurnUserId();
		if (active != null && !active.equals(actorId)) {
			// Encerrar a vez de outra pessoa é do mestre; jogador limpa só a vez dele.
			validateMaster(table.campaignId(), actorId);
		}
		return announce(current.withTurnCleared(clock.instant()), table);
	}

	/** Cena visível por todos. Só o mestre descreve a cena. */
	@Transactional
	public TableState setScene(String tableId, String actorId, String scene) {
		GameState game = stateForWrite(tableId, actorId);
		return announce(game.state().withScene(scene, clock.instant()), game.table());
	}

	// ------------------------------------------------------------------ tokens

	@Transactional(readOnly = true)
	public List<TableToken> tokens(String tableId, String actorId) {
		GameTable table = requireTable(tableId);
		validateViewer(table.campaignId(), actorId);
		return repository.listTableTokens(tableId);
	}

	/** Cria um token. Com ownerId, é token de personagem (o mestre cria para quem pediu). */
	@Transactional
	public TableToken createToken(String tableId, String actorId, String ownerId, String label, String color,
			int x, int y) {
		GameTable table = requireTable(tableId);
		String owner = ownerId;
		if (owner != null && !owner.equals(actorId)) {
			// Criar token para outra pessoa é ato de mestre (o jogador entra com o dele).
			validateMaster(table.campaignId(), actorId);
		}
		else if (owner == null) {
			// Token sem dono é do cenário: peça do mestre.
			validateMaster(table.campaignId(), actorId);
		}
		else {
			validateViewer(table.campaignId(), actorId);
		}
		TableToken token = TableToken.create(newId("roleplay_token"), table.id(), table.campaignId(), owner, label,
			color, x, y, clock.instant());
		TableToken saved = repository.saveTableToken(token);
		announceState(table);
		return saved;
	}

	/** Move um token para outra célula. Dono move o dele; mestre move qualquer um. */
	@Transactional
	public TableToken moveToken(String tableId, String tokenId, String actorId, int x, int y) {
		GameTable table = requireTable(tableId);
		CampaignRole role = campaigns.roleOf(table.campaignId(), actorId);
		boolean master = CampaignPermissions.can(role, CampaignCapability.MANAGE_TABLES);
		TableToken token = repository.findTableToken(tokenId)
			.filter(found -> found.tableId().equals(tableId))
			.orElseThrow(() -> new ApiException(404, "TOKEN_NOT_FOUND", "Token nao encontrado nesta mesa."));
		if (!token.canBeMovedBy(actorId, master)) {
			throw new ApiException(403, "FORBIDDEN", "Este token nao e seu.");
		}
		TableToken moved = token.moveTo(x, y, clock.instant());
		TableToken saved = repository.saveTableToken(moved);
		announceState(table);
		return saved;
	}

	/** Remove um token. O dono remove o dele; o mestre remove qualquer um. */
	@Transactional
	public void removeToken(String tableId, String tokenId, String actorId) {
		GameTable table = requireTable(tableId);
		CampaignRole role = campaigns.roleOf(table.campaignId(), actorId);
		boolean master = CampaignPermissions.can(role, CampaignCapability.MANAGE_TABLES);
		TableToken token = repository.findTableToken(tokenId)
			.filter(found -> found.tableId().equals(tableId))
			.orElseThrow(() -> new ApiException(404, "TOKEN_NOT_FOUND", "Token nao encontrado nesta mesa."));
		if (!token.canBeMovedBy(actorId, master)) {
			throw new ApiException(403, "FORBIDDEN", "Este token nao e seu.");
		}
		repository.removeTableToken(tokenId);
		announceState(table);
	}

	// ------------------------------------------------------------------ voz (porta para o Épapo)

	/**
	 * Abre (ou devolve) a sala de voz da mesa. A mesa passa a referenciar a chamada do Épapo — sem
	 * inventar sala: a sala, o token de entrada e a autorização de cada entrada são os contratos
	 * existentes de {@code /chamadas}, recebidos por porta.
	 */
	@Transactional
	public GameTable openVoice(String tableId, String actorId, VoiceOpener opener) {
		GameTable table = requireTable(tableId);
		validateViewer(table.campaignId(), actorId);
		if (table.hasVoiceRoom()) {
			return table;
		}
		GameTable linked = table.withVoiceRoom(opener.open(actorId), clock.instant());
		return repository.saveTable(linked);
	}

	/** Porta para o serviço de chamadas: abre (ou devolve) a chamada da mesa e devolve o id dela. */
	public interface VoiceOpener {
		String open(String actorId);
	}

	// ------------------------------------------------------------------ auxiliares

	/** O estado atual (ou o vazio de primeira escrita) depois de provar que quem pediu é o mestre. */
	private GameState stateForWrite(String tableId, String actorId) {
		GameTable table = requireTable(tableId);
		validateMaster(table.campaignId(), actorId);
		return new GameState(table, loadState(table));
	}

	private TableState loadState(GameTable table) {
		return repository.findTableState(table.id())
			.orElseGet(() -> TableState.empty(table.id(), table.campaignId(), clock.instant()));
	}

	private record GameState(GameTable table, TableState state) {
	}

	private void validateViewer(String campaignId, String actorId) {
		CampaignRole role = campaigns.roleOf(campaignId, actorId);
		if (!CampaignPermissions.can(role, CampaignCapability.VIEW_CAMPAIGN)) {
			throw new ApiException(403, "NOT_CAMPAIGN_MEMBER", "Voce nao participa desta campanha.");
		}
	}

	private void validateMaster(String campaignId, String actorId) {
		CampaignRole role = campaigns.roleOf(campaignId, actorId);
		if (!CampaignPermissions.can(role, CampaignCapability.MANAGE_TABLES)) {
			throw new ApiException(403, "FORBIDDEN", "Só o mestre muda nesta mesa.");
		}
	}

	/** Grava (ou cria) e avisa os participantes: a mesa mudou. */
	private TableState announce(TableState saved, GameTable table) {
		TableState stored = repository.saveTableState(saved);
		announceState(table);
		return stored;
	}

	private void announceState(GameTable table) {
		List<String> audience = repository.listMembers(table.campaignId()).stream()
			.map(com.socialcoisa.rede.eroleplay.domain.CampaignMember::userId)
			.toList();
		events.publishEvent(new CampaignChatChanged(table.campaignId(), audience));
	}

	private GameTable requireTable(String tableId) {
		return repository.findTableById(tableId)
			.orElseThrow(() -> new ApiException(404, "TABLE_NOT_FOUND", "Mesa nao encontrada."));
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}

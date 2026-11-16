package com.socialcoisa.rede.eroleplay.application;

import java.time.Clock;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.CampaignCapability;
import com.socialcoisa.rede.eroleplay.domain.CharacterSheet;
import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignPermissions;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.WorldEntry;
import com.socialcoisa.rede.eroleplay.domain.WorldKind;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O World Builder: o atlas do mundo da campanha — locais, NPCs, itens, pistas e lore.
 *
 * <p>O mundo é do GRUPO: qualquer participante registra, edita e apaga o que escreveu, porque a
 * anotação de jogador é memória de mesa tanto quanto a lore do mestre. O mestre pode em tudo.
 * A leitura é de quem participa ({@link CampaignCapability#VIEW_CAMPAIGN}) — o atlas nunca sai
 * da campanha. A vinculação com ficha é conferida aqui (ficha tem de ser da campanha) e o id
 * dela não atravessa a API: a projeção entrega o nome do personagem.</p>
 */
@Service
public class WorldService {

	/** Teto do atlas por consulta: o front pagina por atualização, não carrega o mundo de uma vez. */
	private static final int ATLAS_LIMIT = 100;

	private final EroleplayRepository repository;
	private final CampaignService campaigns;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	WorldService(EroleplayRepository repository, CampaignService campaigns, ApplicationEventPublisher events,
			Clock clock) {
		this.repository = repository;
		this.campaigns = campaigns;
		this.events = events;
		this.clock = clock;
	}

	/** Registra uma entrada nova no atlas. Qualquer participante pode. */
	@Transactional
	public WorldEntry create(String campaignId, WorldKind kind, String title, String body, List<String> tags,
			String linkedSheetId, String authorId) {
		campaigns.requireCapability(campaignId, authorId, CampaignCapability.POST_IN_CAMPAIGN);
		if (linkedSheetId != null) {
			requireSheetOfCampaign(campaignId, linkedSheetId);
		}
		WorldEntry entry = WorldEntry.create(campaignId, kind, title, body, tags, linkedSheetId, authorId,
			clock.instant());
		WorldEntry saved = repository.saveWorldEntry(entry);
		announce(campaignId);
		return saved;
	}

	/** Edita uma entrada: o autor no que é dele, o mestre em tudo. */
	@Transactional
	public WorldEntry edit(String campaignId, String entryId, WorldKind kind, String title, String body,
			List<String> tags, String linkedSheetId, String actorId) {
		WorldEntry entry = entryEditableBy(campaignId, entryId, actorId);
		if (linkedSheetId != null) {
			requireSheetOfCampaign(campaignId, linkedSheetId);
		}
		WorldEntry saved = repository.saveWorldEntry(
			entry.edit(kind, title, body, tags, linkedSheetId, clock.instant()));
		announce(campaignId);
		return saved;
	}

	/** Remove uma entrada: o autor no que é dele, o mestre em tudo. */
	@Transactional
	public void remove(String campaignId, String entryId, String actorId) {
		WorldEntry entry = entryEditableBy(campaignId, entryId, actorId);
		repository.removeWorldEntry(entry.id());
		announce(campaignId);
	}

	/**
	 * Uma entrada, para um participante. 404 — e não 403 — quando não existe na mesa de quem
	 * pede: a entrada de outra campanha é "não existe para você".
	 */
	@Transactional(readOnly = true)
	public WorldEntry read(String campaignId, String entryId, String readerId) {
		campaigns.requireCapability(campaignId, readerId, CampaignCapability.VIEW_CAMPAIGN);
		return repository.findWorldEntry(campaignId, entryId)
			.orElseThrow(() -> new ApiException(404, "WORLD_ENTRY_NOT_FOUND", "Entrada do mundo nao encontrada."));
	}

	/**
	 * O atlas da campanha, no recorte de quem pede. Tipo e texto (título, corpo e tags) filtram;
	 * o texto nunca quebra a fronteira — quem não participa não lista nada.
	 */
	@Transactional(readOnly = true)
	public List<WorldEntry> atlas(String campaignId, WorldKind kind, String query, String readerId) {
		campaigns.requireCapability(campaignId, readerId, CampaignCapability.VIEW_CAMPAIGN);
		return repository.listWorldEntries(campaignId, ATLAS_LIMIT).stream()
			.filter(entry -> kind == null || entry.kind() == kind)
			.filter(entry -> query == null || query.isBlank() || matches(entry, query))
			.toList();
	}

	/**
	 * O nome do personagem da ficha vinculada — o que a mesa lê no lugar do id. 404 sem rodeio
	 * quando a vinculação aponta para fora da campanha (não devolve null fingindo que é normal).
	 */
	@Transactional(readOnly = true)
	public String sheetNameOf(String campaignId, String sheetId, String readerId) {
		campaigns.requireCapability(campaignId, readerId, CampaignCapability.VIEW_CAMPAIGN);
		return repository.findSheetById(campaignId, sheetId)
			.map(CharacterSheet::name)
			.orElseThrow(() -> new ApiException(404, "SHEET_NOT_FOUND", "A ficha vinculada nao existe nesta campanha."));
	}

	/** O mundo mudou: o mesmo sinal do chat e da mesa manda os participantes recarregarem o atlas. */
	private void announce(String campaignId) {
		List<String> audience = repository.listMembers(campaignId).stream()
			.map(CampaignMember::userId)
			.toList();
		events.publishEvent(new CampaignChatChanged(campaignId, audience));
	}

	// ------------------------------------------------------------------ auxiliares

	/** A entrada da campanha, só quando quem pede pode editá-la. */
	private WorldEntry entryEditableBy(String campaignId, String entryId, String actorId) {
		campaigns.requireCapability(campaignId, actorId, CampaignCapability.VIEW_CAMPAIGN);
		WorldEntry entry = repository.findWorldEntry(campaignId, entryId)
			.orElseThrow(() -> new ApiException(404, "WORLD_ENTRY_NOT_FOUND", "Entrada do mundo nao encontrada."));
		boolean master = CampaignPermissions.can(roleOf(campaignId, actorId), CampaignCapability.MANAGE_CAMPAIGN);
		if (!entry.canBeEditedBy(actorId, master)) {
			throw new ApiException(403, "FORBIDDEN", "Esta entrada e de outra pessoa da mesa.");
		}
		return entry;
	}

	private CampaignRole roleOf(String campaignId, String userId) {
		return campaigns.roleOf(campaignId, userId);
	}

	/** A vinculação com ficha existe e é DESTA campanha — id de outra mesa não entra. */
	private void requireSheetOfCampaign(String campaignId, String sheetId) {
		if (repository.findSheetById(campaignId, sheetId).isEmpty()) {
			// Ficha não é endereço público: a recusa é a mesma para id inexistente e id alheia.
			throw new ApiException(404, "SHEET_NOT_FOUND", "A ficha vinculada nao existe nesta campanha.");
		}
	}

	/** Busca por texto no título, corpo e tags, sem diferenciar maiúscula. */
	private boolean matches(WorldEntry entry, String query) {
		String needle = query.trim().toLowerCase();
		return entry.title().toLowerCase().contains(needle)
			|| (entry.body() != null && entry.body().toLowerCase().contains(needle))
			|| entry.tags().stream().anyMatch(tag -> tag.contains(needle));
	}
}

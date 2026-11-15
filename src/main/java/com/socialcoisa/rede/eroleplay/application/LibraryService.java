package com.socialcoisa.rede.eroleplay.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignCapability;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.CharacterSheet;
import com.socialcoisa.rede.eroleplay.domain.LibrarySheet;
import com.socialcoisa.rede.eroleplay.domain.SheetAudience;
import com.socialcoisa.rede.eroleplay.domain.SheetEntry;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * A biblioteca do Éroleplay: a ficha que o DONO manda para fora da campanha, com opt-in.
 *
 * <p>Três regras fazem a publicação ser segura por construção:</p>
 * <ul>
 * <li>quem publica é o dono da ficha — o mestre manda na mesa, não na biblioteca;</li>
 * <li>o recorte publicado é o que o dono JÁ abriu à campanha (régua {@link SheetAudience#MEMBER}),
 * lido no momento da publicação: campo privado nunca entra na cópia;</li>
 * <li>a cópia é instantâneo — editar a ficha não reescreve a publicação; republicar é o gesto de
 * atualizar, e despublicar apaga a cópia sem tocar na ficha.</li>
 * </ul>
 *
 * <p>O id interno da ficha não atravessa a API pública: a biblioteca responde pelo id da
 * publicação. A leitura pública não revela quem é o dono nem a campanha de origem como dado de
 * membro — expõe só o que já é público do perfil e da descoberta de campanhas.</p>
 */
@Service
public class LibraryService {

	/** Teto da listagem pública: o front pagina por publicação, não carrega a biblioteca de uma vez. */
	private static final int LIBRARY_LIMIT = 50;

	private final EroleplayRepository repository;
	private final CampaignService campaigns;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	LibraryService(EroleplayRepository repository, CampaignService campaigns, ApplicationEventPublisher events,
			Clock clock) {
		this.repository = repository;
		this.campaigns = campaigns;
		this.events = events;
		this.clock = clock;
	}

	/** Publica (ou republica) a ficha do próprio jogador. A cópia substitui a anterior, se houver. */
	@Transactional
	public LibrarySheet publish(String campaignId, String sheetId, String actorId) {
		CampaignRole role = campaigns.requirePermission(campaignId, actorId, CampaignCapability.VIEW_CAMPAIGN);
		CharacterSheet sheet = repository.findSheetById(campaignId, sheetId)
			.orElseThrow(() -> new ApiException(404, "SHEET_NOT_FOUND", "Ficha nao encontrada."));
		if (!sheet.userId().equals(actorId)) {
			// 404 unificado: ficha de outra pessoa é "não existe para você" — não enumera nem confirma posse.
			throw new ApiException(404, "SHEET_NOT_FOUND", "Ficha nao encontrada.");
		}
		// Mestre publica a própria ficha normalmente; a régua é sempre a do MEMBRO porque é a que
		// o dono já decidiu abrir ao grupo — publicar não pode abrir MAIS do que ele abriu.
		List<SheetEntry> visible = sheet.visibleTo(SheetAudience.MEMBER);
		Map<String, String> publicFields = new LinkedHashMap<>();
		visible.forEach(entry -> publicFields.put(entry.key(), entry.value()));
		Campaign campaign = campaigns.resolve(campaignId);
		Instant now = clock.instant();
		LibrarySheet published = repository.findLibrarySheetBySheet(sheetId)
			.map(existing -> republish(existing, publicFields, now))
			.orElseGet(() -> LibrarySheet.publish(campaignId, sheetId, actorId, sheet.name(),
				campaign.gameSystem(), publicFields, now));
		repository.saveLibrarySheet(published);
		announce(campaignId);
		return published;
	}

	/** Retira a ficha da biblioteca. Apaga a cópia; a ficha de mesa segue intata. */
	@Transactional
	public void unpublish(String campaignId, String sheetId, String actorId) {
		LibrarySheet published = repository.findLibrarySheetBySheet(sheetId)
			.filter(existing -> existing.campaignId().equals(campaignId))
			.orElseThrow(() -> new ApiException(404, "LIBRARY_NOT_FOUND", "Publicacao nao encontrada."));
		if (!published.ownerId().equals(actorId)) {
			// O dono despublica; nem o mestre retira a publicação de outrem — biblioteca é opt-in pessoal.
			throw new ApiException(404, "LIBRARY_NOT_FOUND", "Publicacao nao encontrada.");
		}
		repository.removeLibrarySheet(published.id());
		announce(campaignId);
	}

	/** A publicação pelo id DELA (nunca pelo id da ficha). Leitura pública: sem sessão, sem papel. */
	@Transactional(readOnly = true)
	public LibrarySheet read(String publicationId) {
		return repository.findLibrarySheet(publicationId)
			.orElseThrow(() -> new ApiException(404, "LIBRARY_NOT_FOUND", "Publicacao nao encontrada."));
	}

	/** A biblioteca: publicações recentes com busca opcional por personagem/sistema. Leitura pública. */
	@Transactional(readOnly = true)
	public List<LibrarySheet> list(String query, Instant before, int limit) {
		return repository.listLibrarySheets(query == null ? null : query.trim(), before,
			Math.min(Math.max(limit, 1), LIBRARY_LIMIT));
	}

	/** Republica preservando identidade, autoria e data original — é atualização, não publicação nova. */
	private LibrarySheet republish(LibrarySheet existing, Map<String, String> publicFields, Instant now) {
		LibrarySheet fresh = LibrarySheet.publish(existing.campaignId(), existing.sheetId(), existing.ownerId(),
			existing.characterName(), existing.gameSystem(), publicFields, now);
		return LibrarySheet.restore(existing.id(), existing.campaignId(), existing.sheetId(), existing.ownerId(),
			fresh.characterName(), fresh.gameSystem(), fresh.fields(), existing.publishedAt(), now);
	}

	/** O mundo mudou: o mesmo sinal do chat e da mesa manda os participantes recarregarem. */
	private void announce(String campaignId) {
		List<String> audience = repository.listMembers(campaignId).stream()
			.map(com.socialcoisa.rede.eroleplay.domain.CampaignMember::userId)
			.toList();
		events.publishEvent(new CampaignChatChanged(campaignId, audience));
	}
}

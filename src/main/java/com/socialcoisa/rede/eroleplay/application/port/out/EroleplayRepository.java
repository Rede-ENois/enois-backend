package com.socialcoisa.rede.eroleplay.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequest;
import com.socialcoisa.rede.eroleplay.domain.CampaignJoinRequestStatus;
import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignMembership;
import com.socialcoisa.rede.eroleplay.domain.GameTable;
import com.socialcoisa.rede.eroleplay.domain.TableSeat;

/**
 * Persistência do Éroleplay. Tudo aqui é do domínio de Éroleplay — nada de grupos, posts ou
 * conversas: a campanha é a fronteira de isolamento e o que vive dentro dela não se mistura com
 * o resto da plataforma.
 */
public interface EroleplayRepository {

	void saveCampaign(Campaign campaign);

	Optional<Campaign> findCampaignById(String id);

	Optional<Campaign> findCampaignBySlug(String slug);

	boolean existsCampaignBySlug(String slug);

	/**
	 * Campanhas que aparecem na descoberta (só metadados públicos), mais novas primeiro, com
	 * busca opcional por nome, descrição ou ambientação.
	 */
	List<Campaign> listVisibleCampaigns(String query, Instant before, int limit);

	/** Campanhas em que a pessoa participa, com o papel dela, mais recentes primeiro. */
	List<CampaignMembership> findMemberships(String userId, int limit);

	/** Participantes de cada campanha, numa consulta só (campanha sem participante não aparece no mapa). */
	Map<String, Long> countMembers(Collection<String> campaignIds);

	void saveMember(CampaignMember member);

	Optional<CampaignMember> findMember(String campaignId, String userId);

	List<CampaignMember> listMembers(String campaignId);

	long countMembers(String campaignId);

	void removeMember(String campaignId, String userId);

	/** Mantém o contador desnormalizado da campanha (usado na listagem, para não contar a cada card). */
	void adjustMemberCount(String campaignId, int delta);

	void saveJoinRequest(CampaignJoinRequest request);

	Optional<CampaignJoinRequest> findPendingRequest(String campaignId, String userId);

	Optional<CampaignJoinRequest> findRequestById(String id);

	List<CampaignJoinRequest> listPendingRequests(String campaignId);

	/**
	 * Decisão atômica: só muda se o pedido ainda estiver PENDENTE. Devolve 0 quando outra pessoa
	 * decidiu antes — é o que impede duas aprovações simultâneas de virarem duas associações.
	 */
	int markRequestDecided(String requestId, CampaignJoinRequestStatus status, String actorId, Instant now);

	void saveTable(GameTable table);

	Optional<GameTable> findTableById(String id);

	List<GameTable> listTables(String campaignId);

	void saveSeat(TableSeat seat);

	Optional<TableSeat> findSeat(String tableId, String userId);

	List<TableSeat> listSeats(String tableId);

	void removeSeat(String tableId, String userId);
}

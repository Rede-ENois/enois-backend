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
import com.socialcoisa.rede.eroleplay.domain.CampaignMessage;
import com.socialcoisa.rede.eroleplay.domain.CharacterSheet;
import com.socialcoisa.rede.eroleplay.domain.LibrarySheet;
import com.socialcoisa.rede.eroleplay.domain.ChatChannel;
import com.socialcoisa.rede.eroleplay.domain.DiceRoll;
import com.socialcoisa.rede.eroleplay.domain.RollRequest;
import com.socialcoisa.rede.eroleplay.domain.GameTable;
import com.socialcoisa.rede.eroleplay.domain.SheetTemplate;
import com.socialcoisa.rede.eroleplay.domain.TableSeat;
import com.socialcoisa.rede.eroleplay.domain.WorldEntry;

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

	GameTable saveTable(GameTable table);

	Optional<GameTable> findTableById(String id);

	List<GameTable> listTables(String campaignId);

	void saveSeat(TableSeat seat);

	/** O estado vivo da mesa (um por mesa); vazio quando a mesa ainda não começou. */
	java.util.Optional<com.socialcoisa.rede.eroleplay.domain.TableState> findTableState(String tableId);

	com.socialcoisa.rede.eroleplay.domain.TableState saveTableState(
			com.socialcoisa.rede.eroleplay.domain.TableState state);

	java.util.Optional<com.socialcoisa.rede.eroleplay.domain.TableToken> findTableToken(String tokenId);

	java.util.List<com.socialcoisa.rede.eroleplay.domain.TableToken> listTableTokens(String tableId);

	com.socialcoisa.rede.eroleplay.domain.TableToken saveTableToken(com.socialcoisa.rede.eroleplay.domain.TableToken token);

	void removeTableToken(String tokenId);

	Optional<TableSeat> findSeat(String tableId, String userId);

	List<TableSeat> listSeats(String tableId);

	void removeSeat(String tableId, String userId);

	/** O modelo de ficha da campanha (um por campanha). */
	void saveSheetTemplate(SheetTemplate template);

	Optional<SheetTemplate> findSheetTemplate(String campaignId);

	boolean existsSheetTemplate(String campaignId);

	void deleteSheetTemplate(String campaignId);

	void saveSheet(CharacterSheet sheet);

	Optional<CharacterSheet> findSheet(String campaignId, String userId);

	/** A ficha da campanha por id — usada pela vinculação do World Builder (nunca sai na API). */
	Optional<CharacterSheet> findSheetById(String campaignId, String sheetId);

	List<CharacterSheet> listSheets(String campaignId);

	/** Quantas fichas existem na campanha — recusa a troca de sistema quando já há ficha preenchida. */
	long countSheets(String campaignId);

	// ---- Biblioteca: a copia publica da ficha, com opt-in do dono. ----

	/** Grava (publica ou republica) a copia publica da ficha. */
	void saveLibrarySheet(LibrarySheet librarySheet);

	Optional<LibrarySheet> findLibrarySheet(String publicationId);

	/** A publicacao da ficha informada, se existir (uma ficha tem no maximo uma publicacao). */
	Optional<LibrarySheet> findLibrarySheetBySheet(String sheetId);

	/** A biblioteca: publicacoes recentes, busca opcional por nome do personagem. */
	List<LibrarySheet> listLibrarySheets(String query, Instant before, int limit);

	void removeLibrarySheet(String publicationId);

	// ---- Motor de dados: o registro e so-leitura; a porta nao expõe atualizacao nem remocao. ----

	/** Grava uma rolagem nova. A mesma id gravada duas vezes e colisao de id, nao atualizacao. */
	void saveRoll(DiceRoll roll);

	/** As rolagens recentes da campanha, para o historico da mesa (o recorte de quem vê é do serviço). */
	List<DiceRoll> listRolls(String campaignId, int limit);

	Optional<DiceRoll> findRoll(String campaignId, String rollId);

	void saveRollRequest(RollRequest request);

	Optional<RollRequest> findRollRequest(String campaignId, String requestId);

	/** Os pedidos da campanha, mais recentes primeiro — o painel do mestre. */
	List<RollRequest> listRollRequests(String campaignId);

	/** Os pedidos feitos a um jogador — o que falta e o que ele ja respondeu. */
	List<RollRequest> listRollRequestsOf(String campaignId, String playerId);

	// ---- Chat da campanha: a mensagem e criada uma vez e so a fixacao muda depois. ----

	/** Grava uma mensagem (criação ou a mesma mensagem com o estado de fixação mudado). */
	void saveMessage(CampaignMessage message);

	Optional<CampaignMessage> findMessage(String campaignId, String messageId);

	/** O histórico do canal, mais recente primeiro, com o teto decidido pelo serviço. */
	List<CampaignMessage> listMessages(String campaignId, ChatChannel channel, int limit);

	/** As fixadas da campanha, mais recentes primeiro (os dois canais). */
	List<CampaignMessage> listPinnedMessages(String campaignId, int limit);

	// ---- World Builder: a entidade do mundo é reescrita pelo autor ou mestre; a leitura é da mesa. ----

	/** Grava uma entrada do mundo (criação ou edição — a identidade é o id). */
	WorldEntry saveWorldEntry(WorldEntry entry);

	Optional<WorldEntry> findWorldEntry(String campaignId, String entryId);

	/** O atlas da campanha, mais recente primeiro (o recorte por tipo e busca é do serviço). */
	List<WorldEntry> listWorldEntries(String campaignId, int limit);

	/** Remoção do autor ou do mestre. */
	void removeWorldEntry(String entryId);
}

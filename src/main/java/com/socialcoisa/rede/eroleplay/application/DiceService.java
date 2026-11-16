package com.socialcoisa.rede.eroleplay.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.CampaignCapability;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.DiceExpression;
import com.socialcoisa.rede.eroleplay.domain.DiceRoll;
import com.socialcoisa.rede.eroleplay.domain.RollRequest;
import com.socialcoisa.rede.eroleplay.domain.SheetAudience;
import com.socialcoisa.rede.eroleplay.domain.SheetVisibility;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O motor de dados: rolagem sorteada NO SERVIDOR, registrada de forma imutavel e recortada pelo
 * mesmo publico da ficha.
 *
 * <p>Tres regras moram aqui:</p>
 *
 * <ul>
 * <li><b>O resultado nunca sai do cliente.</b> O corpo descreve o que rolar; o sorteio usa a fonte
 * de aleatoriedade do servidor ({@code SecureRandom} em producao). O corpo que trouxesse resultado
 * seria a mesa mentindo para si mesma.</li>
 * <li><b>O registro nao se reescreve.</b> Nao existe metodo de edicao nem de remocao: o historico
 * e a prova do que aconteceu, e "rolar de novo ate dar o numero bom" e o que ele impede.</li>
 * <li><b>Quem ve o que e papel da campanha.</b> A rolagem nasce com visibilidade (o padrao e a
 * mesa inteira), o jogador pode fechar a propria, e o privado e assunto entre quem rolou e o
 * mestre — a mesma lingua da ficha, e nao um conceito novo.</li>
 * </ul>
 */
@Service
public class DiceService {

	/** Teto do historico por consulta: a mesa nao precisa de rolagem de tres meses atras de uma vez. */
	private static final int HISTORY_LIMIT = 100;

	private final EroleplayRepository repository;
	private final CampaignService campaigns;
	private final CampaignNotifier notifier;
	private final Clock clock;
	private final RandomGenerator random;

	DiceService(EroleplayRepository repository, CampaignService campaigns, CampaignNotifier notifier,
			Clock clock) {
		this.repository = repository;
		this.campaigns = campaigns;
		this.notifier = notifier;
		this.clock = clock;
		this.random = new SecureRandom();
	}

	/**
	 * Rola e registra. O publico informado e o TETO: a visibilidade efetiva e o mais fechado entre
	 * o pedido de quem rola e o teto da rolagem — abrir mais do que se pediu nao e opcao.
	 */
	@Transactional
	public DiceRoll roll(String campaignId, String rollerId, String rawExpression, String label,
			SheetVisibility requestedVisibility) {
		campaigns.requirePermission(campaignId, rollerId, CampaignCapability.ROLL_DICE);
		DiceExpression expression = DiceExpression.parse(rawExpression);
		SheetVisibility visibility = closestOf(requestedVisibility, SheetVisibility.CAMPAIGN);
		DiceRoll roll = DiceRoll.roll(newId("roleplay_roll"), campaignId, rollerId, expression, label, visibility,
			null, random, clock.instant());
		repository.saveRoll(roll);
		return roll;
	}

	/**
	 * O mestre pede uma rolagem a um jogador. O pedido e uma conversa de dois: so ele abre, so o
	 * jogador responde, e a resposta herda o teto de visibilidade do pedido.
	 */
	@Transactional
	public RollRequest requestRoll(String campaignId, String masterId, String playerId, String rawExpression,
			String label, SheetVisibility maxVisibility, String reason) {
		CampaignRole role = campaigns.requirePermission(campaignId, masterId, CampaignCapability.ROLL_DICE);
		if (role != CampaignRole.GAME_MASTER) {
			throw new ApiException(403, "FORBIDDEN", "So o mestre pede rolagem.");
		}
		if (masterId.equals(playerId)) {
			throw new ApiException(400, "ROLL_REQUEST_SELF", "O mestre rola para si mesmo; nao precisa pedir.");
		}
		campaigns.requirePermission(campaignId, playerId, CampaignCapability.ROLL_DICE);
		DiceExpression expression = DiceExpression.parse(rawExpression);
		RollRequest request = RollRequest.open(newId("roleplay_roll_request"), campaignId, masterId, playerId,
			expression, label, closestOf(maxVisibility, SheetVisibility.GAME_MASTER), reason, clock.instant());
		repository.saveRollRequest(request);
		notifier.rollRequested(campaigns.resolve(campaignId), playerId, masterId);
		return request;
	}

	/**
	 * O jogador responde com uma rolagem.
	 *
	 * <p>A expressao respondida TEM que ser a pedida: o jogador pode escolher fechar mais, nunca
	 * mudar o teste — "o mestre pediu d20 e rolou 2d6" seria escolher a prova depois da acusacao.
	 * O pedido nasce PENDING e responde uma unica vez; a segunda resposta e recusada, e a rolagem
	 * que respondeu ja gravada herda a ligacao com o pedido.</p>
	 */
	@Transactional
	public DiceRoll answerRoll(String campaignId, String requestId, String rollerId, String label,
			SheetVisibility requestedVisibility) {
		campaigns.requirePermission(campaignId, rollerId, CampaignCapability.ROLL_DICE);
		RollRequest request = repository.findRollRequest(campaignId, requestId)
			.orElseThrow(() -> new ApiException(404, "ROLL_REQUEST_NOT_FOUND", "Pedido de rolagem nao encontrado."));
		if (!request.playerId().equals(rollerId)) {
			throw new ApiException(403, "FORBIDDEN", "O pedido e para outra pessoa responder.");
		}
		DiceExpression expression = DiceExpression.parse(request.expression());
		if (label != null && !label.isBlank() && request.label() != null && !request.label().isBlank()
				&& !label.trim().equalsIgnoreCase(request.label().trim())) {
			throw new ApiException(400, "ROLL_LABEL_MISMATCH", "O pedido ja tem o rotulo da rolagem.");
		}
		String finalLabel = label == null || label.isBlank() ? request.label() : label.trim();
		SheetVisibility visibility = closestOf(requestedVisibility, request.maxVisibility());
		DiceRoll roll = DiceRoll.roll(newId("roleplay_roll"), campaignId, rollerId, expression, finalLabel,
			visibility, request.id(), random, clock.instant());
		repository.saveRoll(roll);
		repository.saveRollRequest(request.answer(roll.id(), clock.instant()));
		return roll;
	}

	/** As rolagens que quem pede enxerga: o mestre ve a campanha; o jogador ve o dele e o publico. */
	@Transactional(readOnly = true)
	public List<DiceRoll> history(String campaignId, String actorId) {
		CampaignRole role = campaigns.requirePermission(campaignId, actorId, CampaignCapability.ROLL_DICE);
		if (role == CampaignRole.GAME_MASTER) {
			return repository.listRolls(campaignId, HISTORY_LIMIT);
		}
		// O jogador ve as proprias (inclusive as que fechou) e as abertas a campanha; as fechadas
		// de outros nem entram na lista — sumir do historico e a recusa, sem 403 por linha.
		return repository.listRolls(campaignId, HISTORY_LIMIT).stream()
			.filter(roll -> roll.rollerId().equals(actorId)
				|| roll.visibleTo(SheetAudience.MEMBER))
			.toList();
	}

	/**
	 * Uma rolagem, no recorte de quem pede.
	 *
	 * <p>Aqui ficaria o IDOR: a rota recebe o id e devolve o registro. O que impede e o papel
	 * conferido antes da busca e a visibilidade conferida depois dela — jogador nao alcaca rolagem
	 * fechada de outro, nem por id direto no historico.</p>
	 */
	@Transactional(readOnly = true)
	public DiceRoll read(String campaignId, String rollId, String actorId) {
		CampaignRole role = campaigns.requirePermission(campaignId, actorId, CampaignCapability.ROLL_DICE);
		DiceRoll roll = repository.findRoll(campaignId, rollId)
			.orElseThrow(() -> new ApiException(404, "ROLL_NOT_FOUND", "Rolagem nao encontrada."));
		SheetAudience audience = audienceFor(role, roll, actorId);
		if (!roll.visibleTo(audience)) {
			throw new ApiException(403, "FORBIDDEN", "Esta rolagem esta fechada.");
		}
		return roll;
	}

	/** Os pedidos que quem pede enxerga: o mestre ve os que fez; o jogador ve os que recebeu. */
	@Transactional(readOnly = true)
	public List<RollRequest> requests(String campaignId, String actorId) {
		CampaignRole role = campaigns.requirePermission(campaignId, actorId, CampaignCapability.ROLL_DICE);
		if (role == CampaignRole.GAME_MASTER) {
			return repository.listRollRequests(campaignId);
		}
		return repository.listRollRequestsOf(campaignId, actorId);
	}

	/**
	 * Um pedido, no recorte de quem pede: o mestre que o fez e o jogador a quem foi feito leem;
	 * outro participante recebe 403, porque a conversa de dois nao e do grupo.
	 */
	@Transactional(readOnly = true)
	public RollRequest readRequest(String campaignId, String requestId, String actorId) {
		campaigns.requirePermission(campaignId, actorId, CampaignCapability.ROLL_DICE);
		RollRequest request = repository.findRollRequest(campaignId, requestId)
			.orElseThrow(() -> new ApiException(404, "ROLL_REQUEST_NOT_FOUND", "Pedido de rolagem nao encontrado."));
		if (!request.masterId().equals(actorId) && !request.playerId().equals(actorId)) {
			throw new ApiException(403, "FORBIDDEN", "Este pedido nao e seu.");
		}
		return request;
	}

	/** O publico de uma leitura de rolagem: dono, mestre ou outro participante. */
	private SheetAudience audienceFor(CampaignRole role, DiceRoll roll, String actorId) {
		if (roll.rollerId().equals(actorId)) {
			return SheetAudience.OWNER;
		}
		return role == CampaignRole.GAME_MASTER ? SheetAudience.GAME_MASTER : SheetAudience.MEMBER;
	}

	/**
	 * A visibilidade pedida, contida no teto: o que passa do teto NAO e rejeitado aqui, e recuado
	 * para o teto — quem fecha mais, fecha; quem pede mais do que pode, recebe o maximo que pode.
	 * A recusa de valor desconhecido e trabalho do enum.
	 */
	private SheetVisibility closestOf(SheetVisibility requested, SheetVisibility ceiling) {
		if (requested == null) {
			return ceiling;
		}
		return ceiling.reaches(requested) ? requested : ceiling;
	}

	private static String newId(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}

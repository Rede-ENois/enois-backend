package com.socialcoisa.rede.eroleplay.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.eroleplay.application.port.out.EroleplayRepository;
import com.socialcoisa.rede.eroleplay.domain.Campaign;
import com.socialcoisa.rede.eroleplay.domain.CampaignCapability;
import com.socialcoisa.rede.eroleplay.domain.CampaignMember;
import com.socialcoisa.rede.eroleplay.domain.CampaignMessage;
import com.socialcoisa.rede.eroleplay.domain.CampaignRole;
import com.socialcoisa.rede.eroleplay.domain.ChatChannel;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O chat da campanha: canais IC/OOC, apartes privados e fixação do mestre.
 *
 * <p>A campanha é a fronteira: quem não participa não lê nada — nem canal público. O aparte
 * privado é visível só para remetente, destinatário e mestre, e o recorte é aplicado NA RESPOSTA,
 * por leitor: a lista devolve a cada um só o que é seu. Quem não pode ler não recebe a mensagem
 * (sumir é a recusa na listagem); na leitura por id, 404 — não existir para o leitor é o que não
 * vaza.</p>
 *
 * <p>A fixação é do mestre e não reescreve conteúdo. O aviso de "chat mudou" sai por evento
 * publicado dentro da transação e entregue aos streams depois do commit — o stream nunca leva
 * mensagem, o cliente recarrega pelo endpoint que autoriza de novo.</p>
 */
@Service
public class CampaignChatService {

	/** Teto do histórico por consulta: a mesa pede o recente, não a campanha inteira de uma vez. */
	private static final int HISTORY_LIMIT = 100;
	/** Teto dos fixados: o pin perde o sentido se virar segundo histórico. */
	private static final int PINNED_LIMIT = 20;

	private final EroleplayRepository repository;
	private final CampaignService campaigns;
	private final Clock clock;
	private final ApplicationEventPublisher events;

	CampaignChatService(EroleplayRepository repository, CampaignService campaigns, Clock clock,
			ApplicationEventPublisher events) {
		this.repository = repository;
		this.campaigns = campaigns;
		this.clock = clock;
		this.events = events;
	}

	/** Publica no canal. O destinatário só existe no aparte privado, e é para participante. */
	@Transactional
	public CampaignMessage post(String campaignId, ChatChannel channel, String authorId, String recipientId,
			String content) {
		campaigns.requireCapability(campaignId, authorId, CampaignCapability.POST_IN_CAMPAIGN);
		if (recipientId != null) {
			requireParticipant(campaignId, recipientId);
		}
		CampaignMessage message = CampaignMessage.create(campaignId, channel, authorId, recipientId, content,
			clock.instant());
		repository.saveMessage(message);
		announce(campaignId);
		return message;
	}

	/**
	 * O histórico do canal, já no recorte de quem pede: as públicas do canal mais os apartes em
	 * que o leitor é uma das pontas. O mestre, além disso, lê todos os apartes — ele é da mesa.
	 */
	@Transactional(readOnly = true)
	public List<CampaignMessage> history(String campaignId, ChatChannel channel, String readerId) {
		requireParticipant(campaignId, readerId);
		boolean master = isMaster(campaignId, readerId);
		return repository.listMessages(campaignId, channel, HISTORY_LIMIT).stream()
			.filter(message -> message.visibleTo(readerId, master))
			.toList();
	}

	/** As mensagens fixadas da campanha (os dois canais), no recorte de quem pede. */
	@Transactional(readOnly = true)
	public List<CampaignMessage> pinned(String campaignId, String readerId) {
		requireParticipant(campaignId, readerId);
		boolean master = isMaster(campaignId, readerId);
		return repository.listPinnedMessages(campaignId, PINNED_LIMIT).stream()
			.filter(message -> message.visibleTo(readerId, master))
			.toList();
	}

	/**
	 * Uma mensagem, para um leitor. 404 — e não 403 — quando ela não é dele: "não existe para
	 * você" é a recusa que não confirma existência.
	 */
	@Transactional(readOnly = true)
	public CampaignMessage read(String campaignId, String messageId, String readerId) {
		requireParticipant(campaignId, readerId);
		CampaignMessage message = repository.findMessage(campaignId, messageId)
			.orElseThrow(() -> new ApiException(404, "CHAT_MESSAGE_NOT_FOUND", "Mensagem nao encontrada."));
		if (!message.visibleTo(readerId, isMaster(campaignId, readerId))) {
			throw new ApiException(404, "CHAT_MESSAGE_NOT_FOUND", "Mensagem nao encontrada.");
		}
		return message;
	}

	/** Fixa: só o mestre, e a mensagem tem de estar acessível a ele (participante ela já é). */
	@Transactional
	public CampaignMessage pin(String campaignId, String messageId, String masterId) {
		requireMaster(campaignId, masterId);
		CampaignMessage message = requireMessage(campaignId, messageId);
		CampaignMessage pinned = message.pin(masterId, clock.instant());
		repository.saveMessage(pinned);
		announce(campaignId);
		return pinned;
	}

	/** Desfixa: só o mestre. Desfixar o que não está fixada é o mesmo estado. */
	@Transactional
	public CampaignMessage unpin(String campaignId, String messageId, String masterId) {
		requireMaster(campaignId, masterId);
		CampaignMessage message = requireMessage(campaignId, messageId);
		CampaignMessage unpinned = message.unpin();
		repository.saveMessage(unpinned);
		announce(campaignId);
		return unpinned;
	}

	/**
	 * Presença no chat: quem da mesa está com stream aberto agora. O hub conhece os streams por
	 * usuário; a lista de participantes autorizada é que cruza os dois lados — nunca se responde
	 * "quem tem aba aberta" sem antes passar pela fronteira da campanha.
	 */
	@Transactional(readOnly = true)
	public List<CampaignMember> presentMembers(String campaignId, String readerId,
			java.util.function.Predicate<String> hasOpenStream) {
		campaigns.requireCapability(campaignId, readerId, CampaignCapability.VIEW_CAMPAIGN);
		return repository.listMembers(campaignId).stream()
			.filter(member -> hasOpenStream.test(member.userId()))
			.toList();
	}

	private void requireParticipant(String campaignId, String userId) {
		if (campaigns.roleOf(campaignId, userId) == null) {
			throw new ApiException(403, "CAMPAIGN_NOT_A_MEMBER",
				"So quem participa da campanha le o chat dela.");
		}
	}

	private void requireMaster(String campaignId, String userId) {
		if (!isMaster(campaignId, userId)) {
			throw new ApiException(403, "CHAT_MASTER_REQUIRED", "So o mestre fixa mensagens.");
		}
	}

	private boolean isMaster(String campaignId, String userId) {
		return campaigns.roleOf(campaignId, userId) == CampaignRole.GAME_MASTER;
	}

	private CampaignMessage requireMessage(String campaignId, String messageId) {
		return repository.findMessage(campaignId, messageId)
			.orElseThrow(() -> new ApiException(404, "CHAT_MESSAGE_NOT_FOUND", "Mensagem nao encontrada."));
	}

	private void announce(String campaignId) {
		Campaign campaign = campaigns.resolve(campaignId);
		List<String> audience = repository.listMembers(campaignId).stream().map(CampaignMember::userId).toList();
		events.publishEvent(new CampaignChatChanged(campaign.id(), audience));
	}
}

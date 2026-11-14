package com.socialcoisa.rede.eroleplay.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Uma mensagem do chat de campanha.
 *
 * <p>O chat é a cena e a coordenação da mesa, então a mensagem vive dentro da campanha e herda o
 * recorte de participação: quem não participa não lê nada. O conteúdo não se reescreve — o que
 * foi dito foi dito; o único estado que muda depois de criada é a FIXAÇÃO, que é decisão do mestre
 * e não muda uma vírgula do que está escrito.</p>
 *
 * <p>A mensagem privada existe para o aparte da mesa: de um participante a outro, visível só para
 * remetente, destinatário e mestre. Ela não é uma ficha nem um segredo do mestre — é uma conversa
 * a dois dentro da campanha, e o mestre continua sendo da mesa.</p>
 */
public class CampaignMessage {

	/** O aparte é curto: conversa longa tem canal, não mensagem privada. */
	public static final int MAX_LENGTH = 2000;
	private static final int MIN_LENGTH = 1;

	private final String id;
	private final String campaignId;
	private final ChatChannel channel;
	private final String authorId;
	/** Na mensagem privada, o destinatário do aparte; nas públicas, sempre nulo. */
	private final String recipientId;
	private final String content;
	private final Instant createdAt;
	/** Quem fixou, e quando — nulos quando a mensagem não está fixada. */
	private final String pinnedBy;
	private final Instant pinnedAt;

	private CampaignMessage(String id, String campaignId, ChatChannel channel, String authorId, String recipientId,
			String content, Instant createdAt, String pinnedBy, Instant pinnedAt) {
		this.id = id;
		this.campaignId = campaignId;
		this.channel = channel;
		this.authorId = authorId;
		this.recipientId = recipientId;
		this.content = content;
		this.createdAt = createdAt;
		this.pinnedBy = pinnedBy;
		this.pinnedAt = pinnedAt;
	}

	/** Mensagem nova: canal obrigatório, conteúdo entre 1 e 2000 caracteres já aparado. */
	public static CampaignMessage create(String campaignId, ChatChannel channel, String authorId,
			String recipientId, String content, Instant now) {
		if (channel == null) {
			throw new ApiException(400, "CHAT_CHANNEL_REQUIRED", "Escolha um canal (IC ou OOC).");
		}
		if (recipientId != null && recipientId.equals(authorId)) {
			throw new ApiException(400, "CHAT_RECIPIENT_SELF",
				"Mensagem privada para si mesmo nao existe — fale no canal.");
		}
		String trimmed = content == null ? "" : content.trim();
		if (trimmed.length() < MIN_LENGTH) {
			throw new ApiException(400, "CHAT_MESSAGE_EMPTY", "A mensagem esta vazia.");
		}
		if (trimmed.length() > MAX_LENGTH) {
			throw new ApiException(400, "CHAT_MESSAGE_TOO_LONG",
				"A mensagem vai ate " + MAX_LENGTH + " caracteres.");
		}
		return new CampaignMessage(UUID.randomUUID().toString(), campaignId, channel, authorId, recipientId,
			trimmed, now, null, null);
	}

	public static CampaignMessage restore(String id, String campaignId, ChatChannel channel, String authorId,
			String recipientId, String content, Instant createdAt, String pinnedBy, Instant pinnedAt) {
		return new CampaignMessage(id, campaignId, channel, authorId, recipientId, content, createdAt, pinnedBy,
			pinnedAt);
	}

	public String id() {
		return id;
	}

	public String campaignId() {
		return campaignId;
	}

	public ChatChannel channel() {
		return channel;
	}

	public String authorId() {
		return authorId;
	}

	public String recipientId() {
		return recipientId;
	}

	public String content() {
		return content;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public boolean isPrivate() {
		return recipientId != null;
	}

	public boolean isPinned() {
		return pinnedBy != null;
	}

	public String pinnedBy() {
		return pinnedBy;
	}

	public Instant pinnedAt() {
		return pinnedAt;
	}

	/**
	 * Quem pode ler esta mensagem: todos os participantes nas públicas; nas privadas, só remetente,
	 * destinatário e mestre. O recorte é regra de domínio, não decisão do controller.
	 */
	public boolean visibleTo(String readerId, boolean readerIsMaster) {
		if (!isPrivate()) {
			return true;
		}
		return readerIsMaster || authorId.equals(readerId) || recipientId.equals(readerId);
	}

	/**
	 * Fixa a mensagem, preservando o conteúdo e a autoria. Só o mestre fixa — é ele quem separa o
	 * que a mesa precisa reler; e mensagem já fixada fixa de novo é o mesmo estado, não uma segunda
	 * fixação.
	 */
	public CampaignMessage pin(String masterId, Instant now) {
		if (isPinned()) {
			return this;
		}
		return new CampaignMessage(id, campaignId, channel, authorId, recipientId, content, createdAt, masterId, now);
	}

	/** Tira a fixação. Desfixar o que não está fixada é o mesmo estado. */
	public CampaignMessage unpin() {
		if (!isPinned()) {
			return this;
		}
		return new CampaignMessage(id, campaignId, channel, authorId, recipientId, content, createdAt, null, null);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof CampaignMessage message && id.equals(message.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}
}

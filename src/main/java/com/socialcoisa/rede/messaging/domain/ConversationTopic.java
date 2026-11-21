package com.socialcoisa.rede.messaging.domain;

import java.time.Instant;

/**
 * Um topico interno do grupo de conversa: organiza as mensagens por assunto.
 *
 * <p>O topico pertence a EXATAMENTE uma conversa, e a mensagem confere que o topico e daquela
 * conversa antes de gravar — o id existir nao basta. O nome nao tem acento nem duplicata: dois
 * topicos com o mesmo nome no mesmo grupo seriam a mesma coisa escrita duas vezes.</p>
 *
 * <p>Arquivar para de receber mensagem nova sem apagar o que ja esta no topico.</p>
 */
public class ConversationTopic {

	private final String id;
	private final String conversationId;
	private final String name;
	private final int position;
	private final ConversationTopicStatus status;
	private final Instant createdAt;
	private final Instant updatedAt;

	private ConversationTopic(String id, String conversationId, String name, int position,
			ConversationTopicStatus status, Instant createdAt, Instant updatedAt) {
		this.id = id;
		this.conversationId = conversationId;
		this.name = name;
		this.position = position;
		this.status = status;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public static ConversationTopic create(String id, String conversationId, String name, int position, Instant now) {
		return new ConversationTopic(id, conversationId, name, position, ConversationTopicStatus.OPEN, now, now);
	}

	public static ConversationTopic restore(String id, String conversationId, String name, int position,
			ConversationTopicStatus status, Instant createdAt, Instant updatedAt) {
		return new ConversationTopic(id, conversationId, name, position, status, createdAt, updatedAt);
	}

	/** Nome nulo mantem o atual; o nome novo ja chega limpo e validado pelo servico. */
	public ConversationTopic rename(String newName, Instant now) {
		return new ConversationTopic(id, conversationId, newName == null ? name : newName, position, status,
			createdAt, now);
	}

	public ConversationTopic archive(Instant now) {
		return new ConversationTopic(id, conversationId, name, position, ConversationTopicStatus.ARCHIVED,
			createdAt, now);
	}

	public ConversationTopic withPosition(int newPosition, Instant now) {
		return new ConversationTopic(id, conversationId, name, newPosition, status, createdAt, now);
	}

	/** Topico arquivado nao recebe mensagem nova; o historico dele continua legivel. */
	public boolean acceptsMessages() {
		return status == ConversationTopicStatus.OPEN;
	}

	public String id() {
		return id;
	}

	public String conversationId() {
		return conversationId;
	}

	public String name() {
		return name;
	}

	public int position() {
		return position;
	}

	public ConversationTopicStatus status() {
		return status;
	}

	public Instant createdAt() {
		return createdAt;
	}

	public Instant updatedAt() {
		return updatedAt;
	}

}

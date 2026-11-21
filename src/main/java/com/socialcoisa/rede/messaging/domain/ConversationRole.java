package com.socialcoisa.rede.messaging.domain;

import java.util.Locale;

/**
 * Papel de quem participa de um grupo de conversa (kind=GROUP).
 *
 * <p><b>OWNER nao e gravado.</b> O dono e quem CRIOU a conversa ({@code conversations.created_by}):
 * ele ja estava guardado, e um grupo nasce com o criador. Guardar o papel em dois lugares faria a
 * conversa e a linha do participante discordarem sobre quem manda — e a conversa e a fonte, porque
 * e ela que existe antes de qualquer promocao. A tabela de participantes guarda so a promocao a
 * ADMIN; o dono e derivado.</p>
 *
 * <p>Grupos de conversa nao sao o mesmo que os grupos do modulo {@code groups} (que tem outro
 * agregado, com entrada por solicitacao e conteudo próprio). Aqui o grupo e a conversa de chat com
 * mais de duas pessoas.</p>
 */
public enum ConversationRole {

	/** Criador da conversa: manda no grupo e nao pode ser removido. */
	OWNER,

	/** Promovido pelo dono: adiciona e remove gente, mas nao mexe em outro administrador. */
	ADMIN,

	/** Participante comum. */
	MEMBER;

	/** Quem administra o grupo: o dono e os administradores promovidos. */
	public boolean manages() {
		return this == OWNER || this == ADMIN;
	}

	/**
	 * O valor gravado na coluna {@code role}. Valor desconhecido (ou {@code OWNER}, que nao se
	 * grava) vira MEMBER: um papel corrompido nunca pode promover ninguem.
	 */
	public static ConversationRole fromStored(String value) {
		if (value == null) {
			return MEMBER;
		}
		return "ADMIN".equals(value.trim().toUpperCase(Locale.ROOT)) ? ADMIN : MEMBER;
	}
}

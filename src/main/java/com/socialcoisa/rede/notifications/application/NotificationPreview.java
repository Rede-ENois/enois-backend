package com.socialcoisa.rede.notifications.application;

/**
 * Trecho curto do conteúdo de origem (post ou comentário) que a lista de notificações mostra
 * abaixo do corpo, para quem só vê o título entender do que se trata sem abrir o app.
 *
 * <p>É só texto: tags de HTML e marcação de markdown saem. O limite fica bem abaixo de
 * {@code notifications.preview varchar(300)} e cabe nas duas linhas que a linha da lista desenha.</p>
 */
final class NotificationPreview {

	static final int MAX_CHARS = 140;

	private NotificationPreview() {
	}

	/**
	 * Texto legível do conteúdo, ou {@code null} quando não sobra nada — post só com mídia, por
	 * exemplo, em que o card do feed já mostra o vídeo ou a imagem.
	 */
	static String of(String content) {
		if (content == null || content.isBlank()) {
			return null;
		}
		String texto = content
			.replaceAll("<[^>]+>", " ")
			.replaceAll("!\\[[^\\]]*\\]\\([^\\)]*\\)", " ")
			.replaceAll("\\[([^\\]]*)\\]\\([^\\)]*\\)", "$1")
			.replaceAll("(?m)^#{1,6}\\s*", "")
			.replaceAll("[*_`>~]", "")
			.replaceAll("\\s+", " ")
			.trim();
		if (texto.isEmpty()) {
			return null;
		}
		return texto.length() > MAX_CHARS ? texto.substring(0, MAX_CHARS).trim() + "…" : texto;
	}
}

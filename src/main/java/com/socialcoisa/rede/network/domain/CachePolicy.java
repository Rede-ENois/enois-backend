package com.socialcoisa.rede.network.domain;

/**
 * Política de cache HTTP de um objeto do Storage (brief §13), derivada do <b>tipo de conteúdo</b>.
 *
 * <p>O §13 pede cache público para imagem pública e autorização para imagem privada. Separar por
 * caminho seria frágil — a rota informa quem pediu, não o que é. O content-type é a classificação
 * que o próprio upload já declara, e é a mesma que o §12 usa para separar mídia de documento.</p>
 *
 * <p><b>Nada aqui é público por acidente</b>: o padrão é {@link #PRIVATE}, e só mídia sem
 * credencial se torna {@code public}. Um tipo desconhecido não vira cacheável.</p>
 */
public enum CachePolicy {

	/**
	 * Imagem, vídeo ou áudio: cacheável por intermediários. {@code immutable} porque o objeto do
	 * catálogo é imutável — conteúdo alterado é versão nova (id próprio), nunca o mesmo id mudando
	 * por baixo de um cache. Um ano é o padrão de mercado para isso; o {@code ETag} permite
	 * revalidar quando o TTL expirar.
	 */
	PUBLIC_MEDIA("public, max-age=31536000, immutable"),

	/**
	 * Documento ou tipo desconhecido: nunca em cache compartilhado. O conteúdo pode ser de uma
	 * pessoa, e um proxy no caminho não tem como saber se quem pede pode vê-lo.
	 */
	PRIVATE("private, no-store"),

	/**
	 * Entrega a um NÓ de storage ({@code X-Node-Key}). O nó é participante autenticado e busca o
	 * corpo para guardar ou conferir; cache no caminho não teria a quem servir e guardaria bytes de
	 * terceiros fora da rede.
	 */
	NODE("private, no-store");

	/** O valor da linha {@code Cache-Control}. */
	private final String headerValue;

	CachePolicy(String headerValue) {
		this.headerValue = headerValue;
	}

	public String headerValue() {
		return headerValue;
	}

	/**
	 * A política de um objeto entregue a um cliente (não a um nó), pelo content-type.
	 *
	 * @param contentType do catálogo; nulo é desconhecido e cai no padrão privado
	 */
	public static CachePolicy forContentType(String contentType) {
		if (contentType == null) {
			return PRIVATE;
		}
		String type = contentType.toLowerCase(java.util.Locale.ROOT);
		if (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")) {
			return PUBLIC_MEDIA;
		}
		return PRIVATE;
	}
}
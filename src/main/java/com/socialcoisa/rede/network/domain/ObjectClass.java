package com.socialcoisa.rede.network.domain;

/**
 * A classe de um objeto do Storage, derivada do que o catálogo já sabe — não de uma coluna nova.
 *
 * <p>É o que liga um objeto ao tipo de storage node que a colocação prefere (brief §6). A
 * derivação é <b>determinística e visível</b>: content-type para mídia, idade para arquivo. Um
 * objeto nunca muda de classe por decisão implícita de quem lê — só pelo que está no catálogo.</p>
 */
public enum ObjectClass {

	/** Imagem, áudio ou vídeo: prefere {@link StorageNodeKind#MEDIA}. */
	MEDIA,

	/**
	 * Objeto parado há mais tempo que a janela de arquivo: prefere {@link StorageNodeKind#ARCHIVE}.
	 *
	 * <p>"Tempo parado" é {@code createdAt} — o catálogo não mede último acesso. Idade é um piso do
	 * que se pode afirmar: objeto criado há muito tempo pode ter sido lido ontem, e por isso a
	 * colocação <b>prefere</b> arquivo, não exige. Prometer "frio" seria afirmar o que não foi medido.
	 */
	COLD,

	/** Qualquer outro: prefere {@link StorageNodeKind#STANDARD}. */
	DEFAULT;

	/**
	 * Classifica pelo tipo de conteúdo e pela idade.
	 *
	 * @param contentType do catálogo; nulo é desconhecido, não "sem tipo"
	 * @param ageSeconds   desde a criação; nulo é desconhecido
	 * @param coldAfterSeconds janela a partir da qual conta como arquivo
	 */
	public static ObjectClass of(String contentType, Long ageSeconds, long coldAfterSeconds) {
		if (contentType != null) {
			String type = contentType.toLowerCase(java.util.Locale.ROOT);
			if (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")) {
				return MEDIA;
			}
		}
		if (ageSeconds != null && ageSeconds >= coldAfterSeconds) {
			return COLD;
		}
		return DEFAULT;
	}

	/** O tipo de nó que esta classe prefere. */
	public StorageNodeKind preferredKind() {
		return switch (this) {
			case MEDIA -> StorageNodeKind.MEDIA;
			case COLD -> StorageNodeKind.ARCHIVE;
			case DEFAULT -> StorageNodeKind.STANDARD;
		};
	}
}

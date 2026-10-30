package com.socialcoisa.rede.emotes.domain;

/**
 * Enderecos de imagem de figurinha, derivados do provedor + identificador.
 *
 * Duas coisas moram aqui de proposito:
 *
 * 1. O caminho PUBLICO (`/emotes/image/BTTV/123?variant=thumb`) — o navegador nunca fala direto com
 *    o CDN do provedor. O `cdn.frankerfacez.com` falha em ~30% das vezes daqui (timeout de 12s), e
 *    era isso que deixava metade do seletor em quadrado vazio; o proxy do backend guarda o que ja
 *    veio bom e tenta de novo o que falhou.
 * 2. O endereco de ORIGEM, montado so com constantes + identificador ja validado. Nenhum texto de
 *    terceiro entra na URL, entao o proxy nao vira porta para qualquer host.
 *
 * ANIMACAO vem do catalogo, nunca de tentativa as cegas: o FFZ nao responde 404 para o endereco
 * `animated/` de figurinha estatica — a conexao simplesmente fica pendurada. Pedir o arquivo em
 * movimento "para ver se existe" custava 12s por figurinha (dois timeouts de 6s) antes de cair para
 * o estatico. Por isso o catalogo marca o que e animado (a listagem `animated=true` do FFZ publica
 * exatamente esse recorte) e aqui o endereco sai certo de primeira; a queda para o estatico fica so
 * como rede de seguranca para o CDN recusar um arquivo que o provedor diz existir.
 */
public final class EmoteImages {

	/** Escala da grade (leve, sempre estatica) ou do texto (nitida, em movimento quando existe). */
	public enum Variant {

		THUMB("thumb"), FULL("full");

		private final String value;

		Variant(String value) {
			this.value = value;
		}

		public String value() {
			return value;
		}

		public static Variant parse(String value) {
			return "thumb".equalsIgnoreCase(value) ? THUMB : FULL;
		}

	}

	private static final String BTTV_CDN = "https://cdn.betterttv.net/emote/";

	private static final String FFZ_CDN = "https://cdn.frankerfacez.com/emote/";

	private EmoteImages() {
	}

	/** Caminho publico servido por este backend (SEM o prefixo `/api`, que o gateway tira). */
	public static String publicPath(EmoteProvider provider, String id, Variant variant, boolean animated) {
		String path = "/emotes/image/" + provider.name() + "/" + id + "?variant=" + variant.value();
		// O `a` entra no endereco gravado no texto: quando o catalogo descobre a animacao, a URL muda
		// e o navegador nao continua servindo o estatico que guardou por uma semana.
		return variant == Variant.FULL && animated ? path + "&a=1" : path;
	}

	public static String publicPath(Emote emote, Variant variant) {
		return publicPath(emote.provider(), emote.id(), variant, emote.animated());
	}

	/**
	 * Endereco de origem para o proxy buscar.
	 *
	 * BTTV: `1x` e a escala de 28px e `2x` 56px; a animacao so existe em GIF (~300KB no `1x`), por
	 * isso o texto animado usa `1x.gif` — no tamanho em que o chat desenha, o `2x` so pesaria mais.
	 * Estatica usa `2x.png` (nitida). A grade usa `1x.png` sempre.
	 * FFZ: a escala e o proprio segmento (`1`, `2`, `animated/2`).
	 */
	public static String upstream(EmoteProvider provider, String id, Variant variant, boolean animated) {
		return switch (provider) {
			case BTTV -> BTTV_CDN + id + "/" + bttvFile(variant, animated);
			case FFZ -> FFZ_CDN + id + "/" + ffzFile(variant, animated);
		};
	}

	/**
	 * O mesmo endereco em versao ESTATICA, para quando o CDN recusa a animacao. `null` quando o
	 * endereco preferido ja e estatico.
	 */
	public static String fallback(EmoteProvider provider, String id, Variant variant, boolean animated) {
		if (variant == Variant.THUMB || !animated) {
			return null;
		}
		return switch (provider) {
			case BTTV -> BTTV_CDN + id + "/2x.png";
			case FFZ -> FFZ_CDN + id + "/2";
		};
	}

	private static String bttvFile(Variant variant, boolean animated) {
		if (variant == Variant.THUMB) {
			return "1x.png";
		}
		return animated ? "1x.gif" : "2x.png";
	}

	private static String ffzFile(Variant variant, boolean animated) {
		if (variant == Variant.THUMB) {
			return "1";
		}
		return animated ? "animated/2" : "2";
	}
}
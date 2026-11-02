package com.socialcoisa.rede.moderation.domain;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * O que da para saber de um link SEM visitar o destino.
 *
 * Duas coisas, e as duas com pesos diferentes de proposito:
 *
 * - host de plataforma dedicada a conteudo adulto: e conclusivo. Nao existe motivo legitimo para
 *   publicar esse endereco numa rede profissional, entao nem precisa do modelo;
 * - palavra de cunho sexual no caminho/parametros: e so SUSPEITA. "sex" aparece em materia de
 *   educacao, em nome de banda, em pesquisa academica. Serve para mandar o destino para o modelo
 *   olhar com atencao, nunca para punir sozinha.
 *
 * O que decide de verdade e a leitura do destino (titulo e descricao da pagina), que e o que pega o
 * caso do video hospedado num site legitimo.
 */
public final class LinkPolicy {

	private LinkPolicy() {
	}

	/** Plataformas cujo proposito E o conteudo adulto. */
	private static final List<String> HOSTS_ADULTOS = List.of("pornhub.com", "xvideos.com", "xnxx.com",
			"redtube.com", "youporn.com", "xhamster.com", "spankbang.com", "brazzers.com", "onlyfans.com",
			"fansly.com", "chaturbate.com", "cam4.com", "livejasmin.com", "erome.com", "motherless.com",
			"literotica.com", "nhentai.net", "e-hentai.org", "hentaihaven.xxx", "rule34.xxx", "sankakucomplex.com",
			"privacy.com.br", "cameraprive.com", "buceteiro.com", "xvideos-br.com", "pornobr.com", "sexlog.com");

	/** Palavra que sugere conteudo adulto no caminho do link. */
	private static final List<String> TERMOS_SUSPEITOS = List.of("porn", "porno", "pornografia", "xxx", "nsfw",
			"nude", "nudes", "naked", "pelada", "pelado", "hentai", "onlyfans", "sexcam", "sexo", "sexvideo",
			"sex-video", "sex-tape", "sextape", "18mais", "18+", "adulto", "adult", "erotic", "erotico", "putaria",
			"buceta", "pau-grande", "boquete", "gozar", "orgia", "suruba", "camsex", "liveporn", "sexual");

	/** Encurtador: o destino real so aparece seguindo o redirecionamento. */
	private static final List<String> ENCURTADORES = List.of("bit.ly", "tinyurl.com", "goo.gl", "t.co", "ow.ly",
			"is.gd", "buff.ly", "cutt.ly", "shorturl.at", "encurtador.com.br", "encurtador.com", "linktr.ee");

	public static boolean isKnownAdultHost(URI url) {
		String host = hostOf(url);
		if (host == null) {
			return false;
		}
		for (String adulto : HOSTS_ADULTOS) {
			if (host.equals(adulto) || host.endsWith("." + adulto)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isShortener(URI url) {
		String host = hostOf(url);
		return host != null && ENCURTADORES.stream().anyMatch(enc -> host.equals(enc) || host.endsWith("." + enc));
	}

	/** O caminho (ou os parametros) do endereco tem palavra de cunho sexual? */
	public static boolean hasSexualToken(URI url) {
		if (url == null) {
			return false;
		}
		String alvo = ((url.getPath() == null ? "" : url.getPath()) + " "
				+ (url.getQuery() == null ? "" : url.getQuery())).toLowerCase(Locale.ROOT)
			.replace('-', ' ')
			.replace('_', ' ');
		for (String termo : TERMOS_SUSPEITOS) {
			if (alvo.contains(termo.replace('-', ' '))) {
				return true;
			}
		}
		return false;
	}

	private static String hostOf(URI url) {
		if (url == null || url.getHost() == null) {
			return null;
		}
		String host = url.getHost().toLowerCase(Locale.ROOT);
		return host.startsWith("www.") ? host.substring(4) : host;
	}

}

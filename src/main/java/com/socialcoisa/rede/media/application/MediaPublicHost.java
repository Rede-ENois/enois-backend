package com.socialcoisa.rede.media.application;

import java.net.URI;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Endereço público de mídia do ambiente em que este pod está rodando.
 *
 * <p>O endereço gravado no asset é o que valia no momento do upload, e isso é determinístico de
 * propósito (URL assinada apodreceria). O efeito colateral é que um ambiente que nasceu de uma cópia
 * de outro — o QA importado de produção é o caso — devolve para o navegador endereços do ambiente
 * de origem, que continuam funcionando e entregando o conteúdo da origem. Quem lê o QA não acha que
 * está lendo o QA.</p>
 *
 * <p>Duas correções, nenhuma delas mexendo no que está gravado:</p>
 * <ul>
 * <li><b>Escrita</b>: o endereço público configurado ({@code rede.media.s3.public-base-url} ou
 *   {@code rede.media.imagekit.url-endpoint}) precisa ser de um host que PERTENCE a este ambiente —
 *   as origens do app ({@code REDE_FRONTEND_URL}, {@code CORS_ALLOWED_ORIGINS}) mais
 *   {@code rede.backend.url}. Diferente disso, o boot avisa (não derruba: mídia que já existe
 *   continua abrindo e a configuração pode ser corrigida sem parar o serviço).</li>
 * <li><b>Leitura</b>: {@link #localize(String)} troca o host do ambiente de PRODUÇÃO
 *   ({@code rede.site.domains}) pelo deste ambiente, quando o host de mídia configurado é o deste
 *   ambiente. Em produção nada muda: lá o host de mídia é o da produção, e a troca não se aplica.</li>
 * </ul>
 *
 * <p>A troca é de <b>host</b>, nunca de esquema, porta, caminho ou consulta — o endereço continua
 * apontando para o mesmo objeto.</p>
 */
@Component
public class MediaPublicHost {

	private static final Logger LOG = LoggerFactory.getLogger(MediaPublicHost.class);

	private final String mediaHost;
	private final String siteHost;
	private final String mediaBase;

	/**
	 * @param mediaBase endereço público configurado para a mídia (bucket, CDN ou proxy do app);
	 *   vazio significa "não configurado", e aí não há host de produção a trocar.
	 * @param backendUrl host da API deste ambiente ({@code rede.backend.url})
	 * @param frontendUrl host do front deste ambiente ({@code rede.frontend.url})
	 * @param allowedOrigins origens do app ({@code rede.cors.allowed-origins})
	 * @param siteDomains domínios do site, com a produção em primeiro lugar ({@code rede.site.domains})
	 */
	public MediaPublicHost(
			@Value("${rede.media.s3.public-base-url:}") String s3Base,
			@Value("${rede.media.imagekit.url-endpoint:}") String imageKitEndpoint,
			@Value("${rede.backend.url:}") String backendUrl,
			@Value("${rede.frontend.url:}") String frontendUrl,
			@Value("${rede.cors.allowed-origins:}") String allowedOrigins,
			@Value("${rede.site.domains:enois.ia.br}") String siteDomains) {
		this.mediaBase = StringUtils.hasText(s3Base) ? s3Base.trim()
			: (StringUtils.hasText(imageKitEndpoint) ? imageKitEndpoint.trim() : "");
		this.mediaHost = hostOf(mediaBase);
		this.siteHost = hostOf(siteDomains);
		if (StringUtils.hasText(mediaHost) && !belongsToEnvironment(mediaHost, backendUrl, frontendUrl, allowedOrigins)) {
			LOG.warn("host de midia '{}' nao pertence a este ambiente: URLs novas apontariam para outro "
				+ "ambiente. Configure REDE_MEDIA_S3_PUBLIC_BASE_URL (ou IMAGEKIT_URL_ENDPOINT) com o host "
				+ "deste ambiente.", mediaHost);
		}
	}

	/** O host de mídia configurado ({@code null} quando nada foi configurado). */
	public String mediaHost() {
		return mediaHost;
	}

	/**
	 * Endereço que ESTE ambiente entrega ao navegador: o de produção é trocado pelo de mídia deste
	 * ambiente. Tudo mais (outro host, caminho relativo, valor nulo) sai como entrou.
	 */
	public String localize(String url) {
		if (!StringUtils.hasText(url) || !StringUtils.hasText(mediaHost) || mediaHost.equals(siteHost)) {
			return url;
		}
		String host = hostOf(url);
		if (host == null || !belongsToSite(host)) {
			return url;
		}
		int at = indexOfHost(url, host);
		if (at < 0) {
			return url;
		}
		return url.substring(0, at) + mediaHost + url.substring(at + host.length());
	}

	/**
	 * Um host pertence ao ambiente quando é a API, o front ou uma origem do app — ou subdomínio de
	 * qualquer um deles. {@code evil.example.com} não pertence, mesmo contendo o nome.
	 */
	static boolean belongsToEnvironment(String host, String backendUrl, String frontendUrl, String allowedOrigins) {
		for (String candidate : new String[] { backendUrl, frontendUrl }) {
			String known = hostOf(candidate);
			if (known != null && (host.equals(known) || host.endsWith("." + known))) {
				return true;
			}
		}
		for (String origin : allowedOrigins == null ? new String[0] : allowedOrigins.split(",")) {
			String known = hostOf(origin);
			if (known != null && (host.equals(known) || host.endsWith("." + known))) {
				return true;
			}
		}
		return false;
	}

	/** O host pertence ao domínio do site (produção ou espelho, com subdomínios). */
	private boolean belongsToSite(String host) {
		if (!StringUtils.hasText(siteHost)) {
			return false;
		}
		return host.equals(siteHost) || host.endsWith("." + siteHost);
	}

	/**
	 * Primeiro host de uma lista separada por vírgula ({@code enois.ia.br,isweee.com} também é aceito
	 * sem esquema — é a forma de {@code rede.site.domains}).
	 */
	private static String hostOf(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String first = value.split(",")[0].trim();
		if (first.isEmpty()) {
			return null;
		}
		String candidate = first.contains("://") ? first : "https://" + first;
		try {
			String host = URI.create(candidate).getHost();
			return host == null ? null : host.toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException malformed) {
			return null;
		}
	}

	/**
	 * Posição do host no texto. Compara em minúsculas para achar o texto, mas a diferença de tamanho
	 * que importa é a do host em minúsculas — o endereço original pode estar em outra caixa.
	 */
	private static int indexOfHost(String url, String host) {
		String lowered = url.toLowerCase(Locale.ROOT);
		return lowered.indexOf(host);
	}
}
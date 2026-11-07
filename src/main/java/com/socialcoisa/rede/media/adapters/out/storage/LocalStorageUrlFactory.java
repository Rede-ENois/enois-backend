package com.socialcoisa.rede.media.adapters.out.storage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.web.util.UriUtils;

import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;

/**
 * Endereços da mídia no storage local de dev.
 *
 * <p>A chave usa o mesmo formato do S3 (`uploads/{mediaId}/{arquivo}`) e a URL é relativa à API
 * (`/api/storage/{chave}`): o navegador busca na mesma origem do front, que faz proxy para o
 * backend — nada de host/porta fixos e nada de CSP/CORS extra.</p>
 */
class LocalStorageUrlFactory implements StorageUrlFactory {

	private final String apiBase;

	LocalStorageUrlFactory(String apiBase) {
		String value = apiBase == null ? "" : apiBase.trim();
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		this.apiBase = value;
	}

	@Override
	public MediaStorageProvider provider() {
		// O protocolo do cliente é o do S3 (PUT assinado pelo app); local não é um provedor de
		// produção e não ganha valor novo no enum.
		return MediaStorageProvider.S3;
	}

	@Override
	public String objectKey(String mediaId, String fileName) {
		// Codifica segmento a segmento: barras do nome são separadores de verdade (ver
		// S3StorageUrlFactory), e "." / ".." nunca viram nome de arquivo.
		String encoded = Arrays.stream(fileName.split("/", -1))
			.map(segment -> segment.equals(".") || segment.equals("..") ? "_"
				: UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
			.collect(Collectors.joining("/"));
		return "uploads/" + mediaId + "/" + encoded;
	}

	@Override
	public String cdnUrl(String objectKey) {
		return apiBase + "/storage/" + objectKey;
	}

	@Override
	public String uploadUrl(String objectKey) {
		return cdnUrl(objectKey);
	}
}

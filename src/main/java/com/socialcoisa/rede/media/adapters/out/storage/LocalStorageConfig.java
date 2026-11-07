package com.socialcoisa.rede.media.adapters.out.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.socialcoisa.rede.media.application.port.out.MediaStorageGateway;
import com.socialcoisa.rede.media.application.port.out.MediaUploadSigner;
import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;

/**
 * Storage local de desenvolvimento (`rede.media.storage=local`): grava os objetos em disco e os
 * serve pelo mesmo `PUT/GET /api/storage/{chave}` que produção usa para falar com o bucket.
 *
 * <p>Existe porque, sem S3 configurado, `MediaService` responde 503 `UPLOADS_DISABLED` e não há
 * como testar upload localmente. Aqui NÃO há caminho paralelo: o navegador sobe e lê pela mesma
 * rota/proxy, então sanitização de metadados e moderação de imagem continuam rodando. O que muda
 * é só o destino do objeto (arquivo local em vez de bucket).</p>
 *
 * <p>Fora do perfil `dev` o modo não existe: qa/prd seguem com `imagekit`/`s3`. O `provider` do
 * asset é reportado como `S3` porque o protocolo do front (PUT assinado + cabeçalhos) é o mesmo —
 * não é um provedor novo de produção.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rede.media.storage", havingValue = "local")
@EnableConfigurationProperties(LocalStorageConfig.LocalProperties.class)
class LocalStorageConfig {

	/**
	 * A fábrica de URLs do modo local. `@Primary` porque `DevStorageUrlFactory` é um componente
	 * escaneado com `@ConditionalOnMissingBean` e pode ser registrado antes desta configuração;
	 * sem o primário haveria dois candidatos do mesmo tipo e o boot falharia.
	 */
	@Bean
	@Primary
	StorageUrlFactory localStorageUrlFactory(LocalProperties props) {
		return new LocalStorageUrlFactory(props.normalizedApiBase());
	}

	/**
	 * Mesmo contrato do S3 subindo pelo app: URL relativa (mesma origem do front em dev), `PUT` e
	 * `Content-Type` assinado. O `PUT /api/storage/{chave}` exige a sessão do dono do asset.
	 */
	@Bean
	MediaUploadSigner localStorageUploadSigner(LocalProperties props) {
		String base = props.normalizedApiBase();
		return (objectKey, mimeType, contentLength) -> new MediaUploadSigner.SignedUpload(
			base + "/storage/" + objectKey, "PUT", Map.of("Content-Type", mimeType),
			Instant.now().plus(Duration.ofMinutes(60)));
	}

	@Bean
	LocalMediaObjectStore localStorageObjectStore(LocalProperties props) {
		return new LocalMediaObjectStore(props.directory());
	}

	@Bean
	@Primary
	MediaStorageGateway localStorageGateway(LocalMediaObjectStore store) {
		return new LocalMediaStorageGateway(store);
	}

	@ConfigurationProperties(prefix = "rede.media.local")
	static class LocalProperties {

		/** Raiz dos objetos no disco. Vazio = diretório temporário do sistema. */
		private String dir = "";

		/**
		 * Prefixo público da API. Em dev o backend roda com `--server.servlet.context-path=/api`,
		 * então a URL da mídia é `/api/storage/{chave}` (relativa, mesma origem do front).
		 */
		private String apiBase = "/api";

		java.nio.file.Path directory() {
			String value = dir == null || dir.isBlank()
				? System.getProperty("java.io.tmpdir") + "/rede-media-dev"
				: dir.trim();
			return java.nio.file.Path.of(value).toAbsolutePath().normalize();
		}

		String normalizedApiBase() {
			String value = apiBase == null || apiBase.isBlank() ? "" : apiBase.trim();
			while (value.endsWith("/")) {
				value = value.substring(0, value.length() - 1);
			}
			return value;
		}

		String getDir() {
			return dir;
		}

		void setDir(String dir) {
			this.dir = dir;
		}

		String getApiBase() {
			return apiBase;
		}

		void setApiBase(String apiBase) {
			this.apiBase = apiBase;
		}
	}
}

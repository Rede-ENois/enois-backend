package com.socialcoisa.rede.media.adapters.out.storage;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.socialcoisa.rede.media.application.port.out.MediaUploadSigner;
import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;

/**
 * Storage S3, ligado por `REDE_MEDIA_STORAGE=s3` (ver docs/MIDIA-S3.md).
 *
 * O padrao continua sendo o ImageKit: virar o storage e decisao de operacao, por variavel de
 * ambiente, e voltar atras e trocar a variavel de novo — nenhum codigo muda. Enquanto estiver em
 * `imagekit`, este bloco nem e criado e o app se comporta exatamente como hoje.
 *
 * Credencial nao aparece aqui: o S3Presigner/S3Client resolvem pela cadeia padrao do SDK dentro
 * do pod (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY como env var, ou IAM role).
 *
 * `rede.media.s3.endpoint` vazio (default) = AWS real. Preenchido = qualquer gateway
 * S3-compativel (ex: object-storage proprio) — normalmente precisa de
 * `rede.media.s3.force-path-style=true` junto, porque gateway terceiro raramente aceita
 * virtual-hosted-style (`bucket.endpoint`), so path-style (`endpoint/bucket`).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3StorageConfig.S3Properties.class)
@ConditionalOnProperty(name = "rede.media.storage", havingValue = "s3")
class S3StorageConfig {

	private static final Logger LOG = LoggerFactory.getLogger(S3StorageConfig.class);

	@Bean
	StorageUrlFactory s3StorageUrlFactory(S3Properties props) {
		return new S3StorageUrlFactory(props.getBucket(), props.getRegion(), props.getPrefix(),
			props.getPublicBaseUrl());
	}

	@Bean
	S3Presigner s3Presigner(S3Properties props) {
		S3Presigner.Builder builder = S3Presigner.builder().region(Region.of(props.getRegion()));
		if (StringUtils.hasText(props.getEndpoint())) {
			builder.endpointOverride(URI.create(props.getEndpoint()));
		}
		if (props.isForcePathStyle()) {
			builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
		}
		return builder.build();
	}

	@Bean
	MediaUploadSigner mediaUploadSigner(S3Presigner presigner, S3Properties props,
			@org.springframework.beans.factory.annotation.Value("${rede.media.nsfw.enabled:true}") boolean moderationEnabled) {
		// A verificação de imagem acontece no `PUT /api/storage/{chave}`, antes de o objeto chegar
		// ao bucket. Com `upload-through-api=false` o navegador sobe direto no S3 e a checagem
		// seria pulada. Antes isso derrubava o contexto inteiro; uma variável de ambiente errada
		// virava indisponibilidade total. O caminho seguro é forçar a subida pelo app e avisar.
		boolean throughApi = props.uploadThroughApi();
		if (moderationEnabled && !throughApi) {
			LOG.warn("rede.media.s3.upload-through-api=false com moderação de imagens ativa; "
				+ "forçando a subida pelo app para que a verificação aconteça.");
			throughApi = true;
		}
		if (moderationEnabled && !StringUtils.hasText(props.getPublicBaseUrl())) {
			// Sem a base pública não há como devolver a URL do proxy e a moderação pré-storage
			// ficaria inacessível. A varredura pós-publicação continua como rede de segurança.
			LOG.warn("rede.media.s3.public-base-url vazio com moderação de imagens ativa; "
				+ "a subida pode ir direto ao bucket e pular a verificação pré-storage.");
		}
		// Gateway S3-compativel (endpoint configurado) normalmente nao responde CORS: o navegador
		// nao consegue fazer o PUT assinado direto no bucket. Nesse caso a subida passa pelo
		// proprio app (`PUT /api/storage/{chave}`), que e a mesma origem da API.
		return new S3UploadSigner(presigner, props.getBucket(), throughApi ? props.getPublicBaseUrl() : null);
	}

	@Bean
	com.socialcoisa.rede.media.application.port.out.MediaObjectStore s3MediaObjectStore(
			software.amazon.awssdk.services.s3.S3Client s3Client, S3Properties props) {
		return new S3MediaObjectStore(s3Client, props.getBucket());
	}

	@Bean
	software.amazon.awssdk.services.s3.S3Client s3Client(S3Properties props) {
		software.amazon.awssdk.services.s3.S3ClientBuilder builder =
			software.amazon.awssdk.services.s3.S3Client.builder().region(Region.of(props.getRegion()))
				// SDK >= 2.29 manda checksum-trailer (`Content-Encoding: aws-chunked`) por padrao
				// no putObject. Gateway S3-compativel proprio pode nao decodificar esse framing
				// (visto no object-storage b7cloud: grava os bytes crus do wrapper em vez do
				// arquivo real). WHEN_REQUIRED = so manda checksum quando a API exige, igual AWS
				// real continua aceitando sem trailer.
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
				.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
		if (StringUtils.hasText(props.getEndpoint())) {
			builder.endpointOverride(URI.create(props.getEndpoint()));
		}
		if (props.isForcePathStyle()) {
			builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
		}
		return builder.build();
	}

	/** Exclusão no S3: em modo `s3` o gateway do ImageKit NAO existe (ver ImageKitConfig). */
	@Bean
	com.socialcoisa.rede.media.application.port.out.MediaStorageGateway s3MediaStorageGateway(
			software.amazon.awssdk.services.s3.S3Client s3Client, S3Properties props) {
		return new S3MediaStorageGateway(s3Client, props.getBucket());
	}

	@ConfigurationProperties(prefix = "rede.media.s3")
	static class S3Properties {

		private String bucket = "";
		private String region = "us-east-1";
		private String prefix = "uploads/";
		private String endpoint = "";
		private boolean forcePathStyle = false;
		private String publicBaseUrl = "";
		/** null = padrao SEGURO: toda subida passa pelo app (onde os metadados sao limpos). */
		private Boolean uploadThroughApi;

		/**
		 * O gateway do b7cloud recusa PutBucketCors, então o navegador não sobe direto nele. Mesmo
		 * num S3 AWS real o padrão é subir pelo app: o PUT direto assinado pularia a limpeza de
		 * EXIF/GPS. Para abrir mão disso, defina `upload-through-api=false` conscientemente.
		 */
		boolean uploadThroughApi() {
			if (uploadThroughApi != null) {
				return uploadThroughApi;
			}
			return true;
		}

		Boolean getUploadThroughApi() { return uploadThroughApi; }
		void setUploadThroughApi(Boolean uploadThroughApi) { this.uploadThroughApi = uploadThroughApi; }

		String getBucket() { return bucket; }
		void setBucket(String bucket) { this.bucket = bucket; }
		String getRegion() { return region; }
		void setRegion(String region) { this.region = region; }
		String getPrefix() { return prefix; }
		void setPrefix(String prefix) { this.prefix = prefix; }
		String getEndpoint() { return endpoint; }
		void setEndpoint(String endpoint) { this.endpoint = endpoint; }
		boolean isForcePathStyle() { return forcePathStyle; }
		void setForcePathStyle(boolean forcePathStyle) { this.forcePathStyle = forcePathStyle; }
		String getPublicBaseUrl() { return publicBaseUrl; }
		void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
	}
}

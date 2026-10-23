package com.socialcoisa.rede.media.adapters.out.storage;

import java.net.URI;

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
	MediaUploadSigner mediaUploadSigner(S3Presigner presigner, S3Properties props) {
		return new S3UploadSigner(presigner, props.getBucket());
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

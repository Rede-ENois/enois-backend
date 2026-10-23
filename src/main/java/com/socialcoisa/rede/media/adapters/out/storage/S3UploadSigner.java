package com.socialcoisa.rede.media.adapters.out.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.socialcoisa.rede.media.application.port.out.MediaUploadSigner;

/**
 * URL assinada para o browser subir o arquivo direto no bucket.
 *
 * Tres detalhes que importam (e que o README do storage repete para quem for operar):
 *
 *  1. A assinatura inclui o `Content-Type`. Se o cliente mandar tipo diferente do assinado, o S3
 *     recusa — por isso o cabecalho vai na resposta e o front repete exatamente ele.
 *  2. O prazo e o mesmo da janela do asset (15 min), curtinho de proposito.
 *  3. Nao existe credencial no cliente: quem tem permissao e o pod, que assina. O `S3Presigner`
 *     usa a cadeia padrao (IRSA do service account, no cluster).
 */
class S3UploadSigner implements MediaUploadSigner {

	private final S3Presigner presigner;
	private final String bucket;
	/** Quando preenchido, a subida vai pelo proprio app em vez de direto no bucket. */
	private final String proxyBaseUrl;

	S3UploadSigner(S3Presigner presigner, String bucket) {
		this(presigner, bucket, null);
	}

	S3UploadSigner(S3Presigner presigner, String bucket, String proxyBaseUrl) {
		this.presigner = presigner;
		this.bucket = bucket;
		// Mesma forma do `cdnUrl` (base com barra no fim + chave), para a URL de subida e a de
		// leitura apontarem para o MESMO caminho.
		this.proxyBaseUrl = proxyBaseUrl == null || proxyBaseUrl.isBlank() ? null
			: proxyBaseUrl.endsWith("/") ? proxyBaseUrl : proxyBaseUrl + "/";
	}

	/**
	 * A chave recebida JA vem com o prefixo: quem a monta e `S3StorageUrlFactory.objectKey()`, a
	 * mesma que produz o `cdnUrl` gravado no asset. Prefixar de novo aqui mandava o arquivo para
	 * `uploads/uploads/...` enquanto o endereco publico apontava para `uploads/...` — o upload
	 * "dava certo" e a midia nunca aparecia (visto num HAR real em 2026-10-23).
	 */
	@Override
	public SignedUpload sign(String objectKey, String mimeType) {
		Duration validade = Duration.ofMinutes(60);
		// Gateway sem CORS: o navegador nao consegue PUT cross-origin, entao a URL devolvida e a do
		// nosso proxy (mesma origem da API). O app valida a sessao e repassa o corpo ao bucket.
		if (proxyBaseUrl != null) {
			return new SignedUpload(proxyBaseUrl + objectKey, "PUT", Map.of("Content-Type", mimeType),
				Instant.now().plus(validade));
		}
		PutObjectRequest request = PutObjectRequest.builder()
			.bucket(bucket)
			.key(objectKey)
			.contentType(mimeType)
			.build();
		PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
			.signatureDuration(validade)
			.putObjectRequest(request)
			.build());

		return new SignedUpload(presigned.url().toString(), "PUT", Map.of("Content-Type", mimeType),
			Instant.now().plus(validade));
	}
}

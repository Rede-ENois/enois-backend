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
 *
 * `objectKey` que chega aqui JA vem com o prefixo (`rede.media.s3.prefix`) embutido — quem gera e
 * `S3StorageUrlFactory.objectKey()`, a mesma chave que fica gravada no asset e usada depois para
 * ler o objeto de volta. Prepender o prefixo de novo aqui assinaria a escrita para
 * `uploads/uploads/...`, um endereco que `cdnUrl()` nunca aponta — bug real que existiu aqui ate
 * 2026-10-23 e nunca foi exercitado com sucesso em producao (todo asset S3 ate essa data chegou
 * por migracao, nunca por upload novo via este signer).
 */
class S3UploadSigner implements MediaUploadSigner {

	private final S3Presigner presigner;
	private final String bucket;

	S3UploadSigner(S3Presigner presigner, String bucket) {
		this.presigner = presigner;
		this.bucket = bucket;
	}

	@Override
	public SignedUpload sign(String objectKey, String mimeType) {
		Duration validade = Duration.ofMinutes(60);
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

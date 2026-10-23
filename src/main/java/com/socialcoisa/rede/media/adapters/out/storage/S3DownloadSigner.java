package com.socialcoisa.rede.media.adapters.out.storage;

import java.time.Duration;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import com.socialcoisa.rede.media.application.port.out.MediaDownloadSigner;

/**
 * URL assinada para o browser LER o arquivo direto no bucket (ver MediaDownloadSigner).
 *
 * Validade de 60min: a resposta de `GET /media/{id}` carrega esta URL, e o front busca o endereco
 * de novo a cada carregamento de pagina/feed — nao precisa sobreviver alem de uma sessao de
 * navegacao, so nao pode expirar NO MEIO de uma (upload usa a mesma janela, ver S3UploadSigner).
 */
class S3DownloadSigner implements MediaDownloadSigner {

	private final S3Presigner presigner;
	private final String bucket;

	S3DownloadSigner(S3Presigner presigner, String bucket) {
		this.presigner = presigner;
		this.bucket = bucket;
	}

	@Override
	public String sign(String objectKey) {
		Duration validade = Duration.ofMinutes(60);
		GetObjectRequest request = GetObjectRequest.builder()
			.bucket(bucket)
			.key(objectKey)
			.build();
		PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
			.signatureDuration(validade)
			.getObjectRequest(request)
			.build());
		return presigned.url().toString();
	}
}

package com.socialcoisa.rede.media.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.media.application.port.out.MediaUploadSigner;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Configuração de upload do S3 quando a moderação de imagens está ligada.
 *
 * <p>A verificação pré-storage só roda no `PUT /api/storage/{chave}`. Se a operação deixar
 * `upload-through-api=false`, o navegador sobe direto no bucket e a checagem é pulada. Antes o
 * contexto morria no boot; agora a subida é forçada pelo app para preservar a barreira sem
 * transformar uma variável de ambiente errada em indisponibilidade total.</p>
 *
 * <p>Se a moderação estiver ligada e a URL base pública do proxy estiver vazia ou nula, o
 * assinador recusa a operação com código próprio em vez de falhar aberto devolvendo a URL direta
 * do bucket.</p>
 */
class S3StorageConfigTests {

	@Test
	void moderacaoForcaSubidaPeloAppMesmoComUploadThroughApiFalse() {
		S3StorageConfig config = new S3StorageConfig();
		S3StorageConfig.S3Properties props = props("https://enois.exemplo.com/api/storage");
		props.setUploadThroughApi(false);

		// O caminho do proxy não usa o presigner; null basta para provar para onde a URL aponta.
		MediaUploadSigner signer = config.mediaUploadSigner(null, props, true);

		var signed = signer.sign("uploads/media_abc/file.png", "image/png", 42);

		assertThat(signed.url()).isEqualTo("https://enois.exemplo.com/api/storage/uploads/media_abc/file.png");
		assertThat(signed.method()).isEqualTo("PUT");
	}

	@Test
	void moderacaoComPublicBaseUrlVazioNaoGeraUrlDiretaDoBucketELancaExcecao() {
		S3StorageConfig config = new S3StorageConfig();
		S3StorageConfig.S3Properties props = props("");

		MediaUploadSigner signer = config.mediaUploadSigner(null, props, true);

		assertThatThrownBy(() -> signer.sign("uploads/media_abc/file.png", "image/png", 42))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> {
				ApiException apiException = (ApiException) error;
				assertThat(apiException.status()).isEqualTo(503);
				assertThat(apiException.code()).isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
			});
	}

	@Test
	void moderacaoComPublicBaseUrlNuloNaoGeraUrlDiretaDoBucketELancaExcecao() {
		S3StorageConfig config = new S3StorageConfig();
		S3StorageConfig.S3Properties props = props(null);

		MediaUploadSigner signer = config.mediaUploadSigner(null, props, true);

		assertThatThrownBy(() -> signer.sign("uploads/media_abc/file.png", "image/png", 42))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> {
				ApiException apiException = (ApiException) error;
				assertThat(apiException.status()).isEqualTo(503);
				assertThat(apiException.code()).isEqualTo("IMAGE_MODERATION_UNAVAILABLE");
			});
	}

	@Test
	void semModeracaoUploadThroughApiPadraoUsaOProxy() {
		S3StorageConfig config = new S3StorageConfig();
		S3StorageConfig.S3Properties props = props("https://enois.exemplo.com/api/storage");

		MediaUploadSigner signer = config.mediaUploadSigner(null, props, false);

		var signed = signer.sign("uploads/media_abc/file.png", "image/png", 42);

		assertThat(signed.url()).isEqualTo("https://enois.exemplo.com/api/storage/uploads/media_abc/file.png");
	}

	private static S3StorageConfig.S3Properties props(String publicBaseUrl) {
		S3StorageConfig.S3Properties props = new S3StorageConfig.S3Properties();
		props.setBucket("bucket-de-teste");
		props.setRegion("us-east-1");
		props.setPublicBaseUrl(publicBaseUrl);
		return props;
	}
}

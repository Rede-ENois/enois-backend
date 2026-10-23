package com.socialcoisa.rede.media.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * A URL assinada tem de apontar para a MESMA chave que o `cdnUrl` gravado no asset.
 *
 * Foi o que quebrou em producao (HAR de 2026-10-23): o signer prefixava de novo uma chave que ja
 * vinha com o prefixo, e o upload ia para `uploads/uploads/...` enquanto a midia era lida de
 * `uploads/...`. O upload terminava, o post saia com a imagem quebrada.
 */
class S3UploadSignerTests {

	private S3Presigner presigner;

	@AfterEach
	void encerra() {
		if (presigner != null) {
			presigner.close();
		}
	}

	private S3UploadSigner signer(String bucket) {
		// Assinar e operacao local (HMAC), sem rede: credencial falsa e endpoint de mentira bastam.
		presigner = S3Presigner.builder()
			.region(Region.US_EAST_1)
			.endpointOverride(URI.create("https://s3.b7cloud.tech"))
			.credentialsProvider(
				StaticCredentialsProvider.create(AwsBasicCredentials.create("chave", "segredo")))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
		return new S3UploadSigner(presigner, bucket);
	}

	@Test
	void assinaExatamenteAChaveQueSeraLida() {
		var factory = new S3StorageUrlFactory("enois-bucket", "us-east-1", "uploads/",
			"https://s3.b7cloud.tech/enois-bucket");
		String objectKey = factory.objectKey("media_50a1", "media/4e66/foto.jpg");

		String caminhoAssinado = URI.create(signer("enois-bucket").sign(objectKey, "image/jpeg").url())
			.getPath();
		String caminhoPublico = URI.create(factory.cdnUrl(objectKey)).getPath();

		assertThat(caminhoAssinado).isEqualTo(caminhoPublico);
		assertThat(caminhoAssinado).isEqualTo("/enois-bucket/uploads/media_50a1/media/4e66/foto.jpg");
		assertThat(caminhoAssinado).doesNotContain("/uploads/uploads/");
	}

	@Test
	void respeitaPrefixoVazioSemInventarBarra() {
		var factory = new S3StorageUrlFactory("enois-bucket", "us-east-1", "/", "");
		String objectKey = factory.objectKey("media_1", "a.png");

		String caminho = URI.create(signer("enois-bucket").sign(objectKey, "image/png").url()).getPath();

		assertThat(caminho).isEqualTo("/enois-bucket/media_1/a.png");
	}
}

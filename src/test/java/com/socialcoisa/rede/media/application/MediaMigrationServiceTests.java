package com.socialcoisa.rede.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaPublicUrlVerifier;
import com.socialcoisa.rede.media.application.port.out.MediaS3Copier;
import com.socialcoisa.rede.media.application.port.out.MediaSourceDownloader;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.media.domain.MediaStorageProvider;

/**
 * A migracao em duas fases: copia nao troca o cdn_url; falha nao apaga nem relinka; o flip so
 * acontece com a URL publica respondendo; video pede o original ao ImageKit.
 */
class MediaMigrationServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-19T04:00:00Z");
	private static final String IMAGEKIT = "https://ik.imagekit.io/socialcoisa";

	private final FakeRepository repository = new FakeRepository();
	private final FakeDownloader downloader = new FakeDownloader();
	private final FakeCopier copier = new FakeCopier();
	private final FakeVerifier verifier = new FakeVerifier();

	private MediaMigrationService service(boolean flipEnabled) {
		return new MediaMigrationService(repository, downloader, copier, verifier,
			Clock.fixed(NOW, ZoneOffset.UTC), IMAGEKIT, flipEnabled);
	}

	private MediaAsset asset(String id, String mimeType) {
		return MediaAsset.pending(id, new UserId("user_1"), "arquivo", mimeType, 3, "POST_IMAGE",
			"uploads/" + id + "/media%2Ffile", "/uploads/" + id + "/media%2Ffile",
			NOW.minusSeconds(7200), NOW.minusSeconds(3600), MediaStorageProvider.IMAGEKIT);
	}

	@Test
	void copiaConfereEMantemOCdnUrlAteOFlip() {
		MediaAsset media = asset("media_1", "image/jpeg");
		repository.copy.add(media);

		int copied = service(false).copyBatch(10);

		assertThat(copied).isEqualTo(1);
		MediaAsset saved = repository.byId.get("media_1");
		assertThat(saved.storageProvider()).isEqualTo(MediaStorageProvider.S3);
		assertThat(saved.s3CdnUrl()).isEqualTo("https://s3.example/uploads/media_1/media/file");
		assertThat(saved.s3MigratedAt()).isEqualTo(NOW);
		// A fase 1 NAO troca o que o app le.
		assertThat(saved.cdnUrl()).isEqualTo("/uploads/media_1/media%2Ffile");
		assertThat(downloader.urls).containsExactly(IMAGEKIT + "/uploads/media_1/media/file");
		assertThat(copier.keys).containsExactly("uploads/media_1/media/file");
	}

	@Test
	void videoPedeOOriginalAoCdnDoImageKit() {
		MediaAsset media = asset("media_2", "video/mp4");
		repository.copy.add(media);

		service(false).copyBatch(10);

		assertThat(downloader.urls).containsExactly(IMAGEKIT + "/uploads/media_2/media/file?tr=orig-true");
	}

	@Test
	void falhaNaoTrocaNadaENaoApagaOAsset() {
		repository.copy.add(asset("media_3", "image/jpeg"));
		copier.failure = true;

		int copied = service(false).copyBatch(10);

		assertThat(copied).isZero();
		MediaAsset saved = repository.byId.get("media_3");
		assertThat(saved.storageProvider()).isEqualTo(MediaStorageProvider.IMAGEKIT);
		assertThat(saved.s3CdnUrl()).isNull();
		assertThat(saved.cdnUrl()).isEqualTo("/uploads/media_3/media%2Ffile");
		assertThat(saved.s3MigrationAttempts()).isEqualTo(1);
		assertThat(saved.s3LastError()).contains("S3");
	}

	@Test
	void flipSoAconteceComAUrlPublicaRespondendo() {
		MediaAsset media = asset("media_4", "image/jpeg");
		media.markS3Copy("https://s3.example/uploads/media_4/media/file", NOW);
		repository.flip.add(media);

		verifier.healthy = false;
		assertThat(service(true).flipBatch(10)).isZero();
		assertThat(media.cdnUrl()).isEqualTo("/uploads/media_4/media%2Ffile");

		// O mesmo asset, agora com a leitura publica OK.
		MediaAsset again = asset("media_4", "image/jpeg");
		again.markS3Copy("https://s3.example/uploads/media_4/media/file", NOW);
		repository.flip.clear();
		repository.flip.add(again);
		verifier.healthy = true;

		assertThat(service(true).flipBatch(10)).isEqualTo(1);
		assertThat(again.cdnUrl()).isEqualTo("https://s3.example/uploads/media_4/media/file");
	}

	@Test
	void flipDesligadoNaoMexeEmNada() {
		MediaAsset media = asset("media_5", "image/jpeg");
		media.markS3Copy("https://s3.example/uploads/media_5/media/file", NOW);
		repository.flip.add(media);
		verifier.healthy = true;

		assertThat(service(false).flipBatch(10)).isZero();
		assertThat(media.cdnUrl()).isEqualTo("/uploads/media_5/media%2Ffile");
	}

	@Test
	void migracaoOportunistaCopiaQuandoGanhaOClaim() {
		MediaAsset media = asset("media_lazy_1", "image/jpeg");
		repository.save(media);
		repository.claimGranted = true;

		assertThat(service(false).copyOne("media_lazy_1")).isTrue();

		MediaAsset saved = repository.byId.get("media_lazy_1");
		assertThat(saved.s3CdnUrl()).isEqualTo("https://s3.example/uploads/media_lazy_1/media/file");
		// A referencia so muda no flip (cdn_url segue o antigo ate a verificacao publica).
		assertThat(saved.cdnUrl()).isEqualTo("/uploads/media_lazy_1/media%2Ffile");
		assertThat(repository.claimed).containsExactly("media_lazy_1");
	}

	@Test
	void migracaoOportunistaSemClaimNaoCopiaNemBaixa() {
		MediaAsset media = asset("media_lazy_2", "image/jpeg");
		repository.save(media);
		repository.claimGranted = false;

		assertThat(service(false).copyOne("media_lazy_2")).isFalse();
		assertThat(downloader.urls).isEmpty();
		assertThat(copier.keys).isEmpty();
	}

	@Test
	void falhaNaCopiaOportunistaRegistraErroSemPerderAReferencia() {
		MediaAsset media = asset("media_lazy_3", "image/jpeg");
		repository.save(media);
		repository.claimGranted = true;
		downloader.failNext = true;

		assertThat(service(false).copyOne("media_lazy_3")).isFalse();

		MediaAsset saved = repository.byId.get("media_lazy_3");
		assertThat(saved.cdnUrl()).isEqualTo("/uploads/media_lazy_3/media%2Ffile");
		assertThat(saved.s3LastError()).isNotNull();
	}

	@Test
	void midiaJaCopiadaNaoEntraDeNovoNaFila() {
		MediaAsset media = asset("media_lazy_4", "image/jpeg");
		media.markS3Copy("https://s3.example/uploads/media_lazy_4/media/file", NOW);
		repository.save(media);
		repository.claimGranted = true;

		assertThat(service(false).copyOne("media_lazy_4")).isFalse();
		assertThat(downloader.urls).isEmpty();
	}

	private static final class FakeRepository implements MediaAssetRepository {
		private final List<MediaAsset> copy = new ArrayList<>();
		private final List<MediaAsset> flip = new ArrayList<>();
		private final Map<String, MediaAsset> byId = new HashMap<>();
		private final List<String> claimed = new ArrayList<>();
		private boolean claimGranted = true;

		@Override
		public boolean hasReportCapture(java.util.Collection<String> ids) {
			return ids != null && byId.values().stream()
				.anyMatch(asset -> ids.contains(asset.id())
					&& com.socialcoisa.rede.media.application.MediaService.REPORT_CAPTURE_PURPOSE.equals(asset.purpose()));
		}

		@Override
		public boolean claimForS3Copy(String id, java.time.Instant now, java.time.Instant staleBefore, int maxAttempts) {
			claimed.add(id);
			boolean permitido = claimGranted;
			claimGranted = false; // claim e de UMA vez: a chamada seguinte perde (outra replica/rodada)
			return permitido;
		}

		@Override
		public MediaAsset save(MediaAsset asset) {
			byId.put(asset.id(), asset);
			return asset;
		}

		@Override
		public Optional<MediaAsset> findById(String id) {
			return Optional.ofNullable(byId.get(id));
		}

		@Override
		public List<MediaAsset> findByIds(Collection<String> ids) {
			return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
		}

		@Override
		public List<MediaAsset> findForS3Copy(int limit, Instant before, int maxAttempts) {
			return List.copyOf(copy);
		}

		@Override
		public List<MediaAsset> findForS3Flip(int limit) {
			return List.copyOf(flip);
		}

		@Override
		public long countByStorageProvider(MediaStorageProvider provider) {
			return 0;
		}

		@Override
		public long countMigratedToS3() {
			return 0;
		}

		@Override
		public long countWithMigrationError() {
			return 0;
		}

		@Override
		public Optional<String> lastMigrationError() {
			return Optional.empty();
		}

		@Override
		public long countActiveOnS3() {
			return 0;
		}

		@Override
		public long countAll() {
			return 0;
		}

		@Override
		public void recordDimensions(String id, int width, int height) {
		}

		@Override
		public void recordDimensionFailure(String id) {
		}

		@Override
		public List<MediaAsset> findForDimensionBackfill(int limit, int maxAttempts, java.time.Instant createdBefore) {
			return List.of();
		}
	}

	private static final class FakeDownloader implements MediaSourceDownloader {
		private final List<String> urls = new ArrayList<>();
		private boolean failNext;

		@Override
		public Path download(String sourceUrl) {
			urls.add(sourceUrl);
			if (failNext) {
				throw new IllegalStateException("origem respondeu 404");
			}
			try {
				Path file = Files.createTempFile("migration-test-", ".bin");
				Files.writeString(file, "abc");
				return file;
			}
			catch (Exception exception) {
				throw new IllegalStateException(exception);
			}
		}
	}

	private static final class FakeCopier implements MediaS3Copier {
		private final List<String> keys = new ArrayList<>();
		private boolean failure;

		@Override
		public String copy(String objectKey, Path source, String mimeType) {
			if (failure) {
				throw new IllegalStateException("S3 recusou a copia");
			}
			keys.add(objectKey);
			return "https://s3.example/" + objectKey;
		}
	}

	private static final class FakeVerifier implements MediaPublicUrlVerifier {
		private boolean healthy;

		@Override
		public boolean verify(String url, String mimeType) {
			return healthy;
		}
	}
}

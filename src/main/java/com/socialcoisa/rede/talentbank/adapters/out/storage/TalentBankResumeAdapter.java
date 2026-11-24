package com.socialcoisa.rede.talentbank.adapters.out.storage;

import java.io.InputStream;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.shared.web.ApiException;
import com.socialcoisa.rede.talentbank.application.port.out.TalentBankResumeReader;

/**
 * O currículo servido pelo backend, sob autorização.
 *
 * <p>O endereço guardado no perfil aponta para o storage público de mídia — por isso ele NÃO pode
 * ser repassado ao recrutador. Aqui o endereço vira o asset, o asset é conferido contra o dono
 * da inscrição e o corpo do objeto é lido pelo próprio backend. Quem não é o dono do arquivo não
 * recebe nada, mesmo que mande o endereço de outra pessoa.</p>
 */
@Repository
class TalentBankResumeAdapter implements TalentBankResumeReader {

	/** A chave do storage carrega o id do asset: `media_<id>`. */
	private static final Pattern MEDIA_ID = Pattern.compile("media_[0-9a-fA-F-]{8,64}");

	private final MediaAssetRepository assets;
	private final ObjectProvider<MediaObjectStore> storeProvider;

	TalentBankResumeAdapter(MediaAssetRepository assets, ObjectProvider<MediaObjectStore> storeProvider) {
		this.assets = assets;
		this.storeProvider = storeProvider;
	}

	@Override
	public Optional<ResumeFile> read(String candidateId, String resumeUrl, String resumeName) {
		if (!StringUtils.hasText(candidateId) || !StringUtils.hasText(resumeUrl)) {
			return Optional.empty();
		}
		Matcher matcher = MEDIA_ID.matcher(resumeUrl);
		if (!matcher.find()) {
			return Optional.empty();
		}
		MediaAsset asset = assets.findById(matcher.group()).orElse(null);
		if (asset == null || !asset.belongsTo(new UserId(candidateId))) {
			return Optional.empty();
		}
		if (!"application/pdf".equalsIgnoreCase(asset.mimeType())) {
			return Optional.empty();
		}
		MediaObjectStore store = storeProvider.getIfAvailable();
		if (store == null) {
			throw new ApiException(503, "MEDIA_STORAGE_DISABLED", "Armazenamento de midia indisponivel.");
		}
		MediaObjectStore.StoredObject object;
		try {
			object = store.read(asset.objectKey(), null);
		}
		catch (RuntimeException missing) {
			return Optional.empty();
		}
		InputStream body = object.body();
		if (body == null) {
			return Optional.empty();
		}
		String contentType = StringUtils.hasText(object.contentType()) ? object.contentType() : asset.mimeType();
		return Optional.of(new ResumeFile(body, object.length(), contentType, fileName(resumeName, asset)));
	}

	/** O nome do arquivo vem do perfil; sem ele, o nome original do asset. */
	private static String fileName(String resumeName, MediaAsset asset) {
		String name = StringUtils.hasText(resumeName) ? resumeName.trim() : asset.fileName();
		if (!StringUtils.hasText(name)) {
			return "curriculo.pdf";
		}
		return name.replaceAll("[\\r\\n\"]", "_");
	}
}

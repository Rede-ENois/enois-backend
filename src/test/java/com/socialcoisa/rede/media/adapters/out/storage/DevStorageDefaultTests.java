package com.socialcoisa.rede.media.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.media.application.port.out.MediaUploadSigner;
import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;

/**
 * O perfil `dev` tem de permitir upload sem nenhuma variável extra: sem isso, testar mídia
 * localmente depende de um bucket. O `application-dev.properties` liga `rede.media.storage=local`;
 * este teste falha se alguém remover esse default.
 */
@SpringBootTest
class DevStorageDefaultTests {

	@Autowired
	private StorageUrlFactory storageUrlFactory;

	@Autowired
	private ObjectProvider<MediaUploadSigner> uploadSigner;

	@Test
	void devProfileEnablesLocalStorageAndUploadsByDefault() {
		assertThat(storageUrlFactory).isInstanceOf(LocalStorageUrlFactory.class);
		assertThat(uploadSigner.getIfAvailable()).isNotNull();
	}
}

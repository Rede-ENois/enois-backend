package com.socialcoisa.rede.media.adapters.out.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.socialcoisa.rede.media.application.port.out.StorageUrlFactory;

/**
 * O default de qa/prd continua o ImageKit — ligar o S3 (ou o modo local de dev) é decisão de
 * operação, nunca um deploy mudando o storage sozinho. A suíte roda com o perfil `dev`, que agora
 * opta por `local` para o upload funcionar sem bucket; por isso o modo de produção é fixado aqui.
 */
@SpringBootTest(properties = "rede.media.storage=imagekit")
class StorageDefaultTests {

	@Autowired
	private StorageUrlFactory storageUrlFactory;

	@Test
	void imageKitModeUsesTheImageKitUrlFactory() {
		assertThat(storageUrlFactory).isInstanceOf(ImageKitStorageUrlFactory.class);
	}
}

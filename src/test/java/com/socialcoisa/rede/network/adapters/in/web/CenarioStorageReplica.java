package com.socialcoisa.rede.network.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.socialcoisa.rede.network.application.NodeKeyService;
import com.socialcoisa.rede.network.application.StorageCatalogService;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.network.domain.NodeProfile;
import com.socialcoisa.rede.network.domain.NodeStatus;
import com.socialcoisa.rede.network.domain.StorageObject;
import com.socialcoisa.rede.network.domain.StoragePlacement;
import com.socialcoisa.rede.shared.security.Hashes;

/** Fixtures sintéticas por cenário; sem autenticação de usuário nem limpeza de tabelas globais. */
abstract class CenarioStorageReplica {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired StorageCatalogService storage;
	@Autowired StorageCatalogRepository catalog;
	@Autowired NetworkNodeRepository nodes;
	@Autowired NodeKeyService keys;
	@Autowired PlatformTransactionManager transactions;

	private final List<Path> arquivos = new ArrayList<>();

	No no(NodeProfile perfil) {
		String id = "nd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			nodes.create(id, "no-sintetico", "ator-sintetico", List.of(perfil), null, null,
				2, 4096, 102400, 100, Instant.now());
			nodes.updateStatus(id, NodeStatus.ACTIVE, "ator-sintetico", Instant.now());
		});
		return new No(id, keys.issue(id, "ator-sintetico").plainTextKey());
	}

	StorageObject objeto(String conteudo, No no, String tipo, Instant criadoEm, boolean gravar) throws Exception {
		String bucket = "regressao-" + UUID.randomUUID().toString().substring(0, 8);
		StorageObject pending = storage.beginUpload("ator-sintetico", bucket, "corpo.bin", tipo);
		byte[] bytes = conteudo.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(catalog.markStored(pending.id(), bytes.length, Hashes.sha256Hex(conteudo), criadoEm)).isTrue();
		jdbc.update("update network_storage_objects set created_at = ? where id = ?", criadoEm, pending.id());
		StorageObject object = catalog.findById(pending.id()).orElseThrow();
		colocacao(object, no, StoragePlacement.State.OK);
		if (gravar) {
			Path file = storage.bodyPath(object.id());
			arquivos.add(file);
			Files.write(file, bytes);
		}
		return object;
	}

	void colocacao(StorageObject object, No no, StoragePlacement.State estado) {
		catalog.savePlacement(new StoragePlacement(object.id(), 0, no.id(), object.bytes(), object.sha256(),
			estado, Instant.now()));
	}

	MockHttpServletRequestBuilder download(String sha256, No no) {
		return get("/network/storage/objects/by-digest/" + sha256)
			.header("X-Node-Id", no.id()).header("X-Node-Key", no.chave());
	}

	@AfterEach
	void limparArquivosDoCenario() throws Exception {
		for (Path file : arquivos) {
			Files.deleteIfExists(file);
		}
	}

	record No(String id, String chave) { }
}

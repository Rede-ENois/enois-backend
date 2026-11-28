package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.StorageCatalogRepository;
import com.socialcoisa.rede.shared.audit.AuditTrail;

/**
 * Coleta dos corpos do Control Plane que não têm dono no catálogo (brief do Storage, "os dados têm
 * de sobreviver ao próprio Control Plane").
 *
 * <p><b>Como o órfão nasce.</b> {@code StorageCatalogService.store} grava o corpo em
 * {@code rede.storage.root} e <b>depois</b> marca o objeto — a ordem é a certa (registrar "tem
 * cópia" antes de o arquivo existir daria objeto servível sem bytes) e o preço é a queda no meio: o
 * processo morto entre os dois passos deixa o arquivo de um id que o catálogo não descreve. Como o
 * nome do arquivo é o id do objeto e a próxima gravação usa outro id, o resíduo ficaria para
 * sempre.</p>
 *
 * <p><b>Como o coletor decide.</b> Só {@code .bin} é corpo — o temporário de escrita
 * ({@code .upload-*.part}) não entra: apagá-lo esconderia o upload que está rodando agora (mesma
 * régua do {@code objectIds()} do nó). Um {@code .bin} é órfão quando o catálogo não tem o id, e aí
 * há dois casos que a coleta NÃO trata igual:</p>
 *
 * <ul>
 * <li><b>id de nó</b> ({@code catalog.storageNodeExists}): pode ser a cópia de uma colocação que o
 * banco perdeu. O nó é a fonte da verdade do que guarda — o arquivo entra no relatório e fica;</li>
 * <li><b>id do Control Plane</b> ({@code so_}): resíduo de gravação interrompida. Apaga — mas só se
 * a idade passar da janela de seguro, porque entre criar o arquivo e gravar a linha o upload legítimo
 * de agora tem o arquivo no disco.</li>
 * </ul>
 *
 * <p>A coleta não roda sozinha: é o operador que chama (rota administrativa, como a coleta de
 * lápides e a varredura de multipart), e {@code dryRun} lista sem apagar. Sem raiz a resposta é
 * {@code installed: false} — não "nenhum órfão".</p>
 */
@Service
public class StorageOrphanCollector {

	/**
	 * Idade mínima de um arquivo sem linha para ser apagado. Cobre a gravação em andamento: um upload
	 * grande passa minutos entre o arquivo existir e a linha ser gravada.
	 */
	static final long SAFE_AGE_SECONDS = 60 * 60;

	/** O que uma passada viu e fez. */
	public record Report(boolean installed, Path root, List<String> orphansToRemove, List<String> keptNodeBodies,
			List<String> removed, List<String> skippedRecent, List<String> failed) {

		/** Nada foi apagado nesta passada (leitura). */
		public boolean dryRun() {
			return removed.isEmpty();
		}
	}

	private final StorageCatalogRepository catalog;
	private final Path root;
	private final AuditTrail audit;
	private final java.time.Clock clock;

	/** Sem trilha de auditoria, em contexto de teste; em produção o bean existe. */
	StorageOrphanCollector(StorageCatalogRepository catalog, String storageRoot, java.time.Clock clock) {
		this(catalog, storageRoot, null, clock);
	}

	@Autowired
	StorageOrphanCollector(StorageCatalogRepository catalog,
			@Value("${rede.storage.root:${java.io.tmpdir}/enois-storage}") String storageRoot, AuditTrail audit,
			java.time.Clock clock) {
		this.catalog = catalog;
		this.root = Path.of(storageRoot).toAbsolutePath().normalize();
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Lista — e, sem {@code dryRun}, apaga — os corpos sem linha no catálogo.
	 */
	public Report collect(boolean dryRun) {
		if (!Files.isDirectory(root)) {
			return new Report(false, root, List.of(), List.of(), List.of(), List.of(), List.of());
		}
		long agora = clock.millis();
		List<String> apagaveis = new ArrayList<>();
		List<String> deNo = new ArrayList<>();
		List<String> removidos = new ArrayList<>();
		List<String> recentes = new ArrayList<>();
		List<String> falhas = new ArrayList<>();
		Set<String> nomes;
		try (Stream<Path> arquivos = Files.list(root)) {
			nomes = arquivos.filter(Files::isRegularFile)
				.map(p -> p.getFileName().toString())
				.filter(nome -> nome.endsWith(".bin"))
				.map(nome -> nome.substring(0, nome.length() - ".bin".length()))
				// Sobra um `.bin` sem id no nome (`.bin` só): não é corpo de nada.
				.filter(id -> !id.isEmpty())
				.collect(java.util.stream.Collectors.toSet());
		}
		catch (IOException ilegivel) {
			// Sem poder listar o diretório não há o que afirmar sobre órfãos: "nenhum" seria mentira.
			falhas.add("RAIZ_ILEGIVEL:" + ilegivel.getClass().getSimpleName());
			return new Report(true, root, List.of(), List.of(), List.of(), List.of(), falhas);
		}
		for (String id : nomes.stream().sorted().toList()) {
			if (catalog.findById(id).isPresent()) {
				continue;
			}
			if (catalog.storageNodeExists(id)) {
				// Id de NÓ: pode ser cópia de objeto que o catálogo perdeu; listar e não apagar.
				deNo.add(id);
				continue;
			}
			Path arquivo = root.resolve(id + ".bin");
			long idadeMs;
			try {
				idadeMs = agora - Files.getLastModifiedTime(arquivo).toMillis();
			}
			catch (IOException naoMedido) {
				falhas.add(id);
				continue;
			}
			// Gravação em andamento: o arquivo existe antes da linha, por desenho.
			if (idadeMs < SAFE_AGE_SECONDS * 1000L) {
				recentes.add(id);
				continue;
			}
			apagaveis.add(id);
			if (!dryRun) {
				try {
					if (Files.deleteIfExists(arquivo)) {
						removidos.add(id);
					}
				}
				catch (IOException naoApagou) {
					falhas.add(id);
				}
			}
		}
		if (audit != null) {
			audit.record("system", "STORAGE_ORPHAN_COLLECT_RUN", "storage",
				"{\"dryRun\":" + dryRun + ",\"orphans\":" + apagaveis.size() + ",\"removed\":" + removidos.size()
					+ ",\"keptNodeBodies\":" + deNo.size() + ",\"skippedRecent\":" + recentes.size() + ",\"failed\":"
					+ falhas.size() + "}");
		}
		return new Report(true, root, List.copyOf(apagaveis), List.copyOf(deNo), List.copyOf(removidos),
			List.copyOf(recentes), List.copyOf(falhas));
	}
}
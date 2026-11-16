package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * O corpo de objeto que este nó da rede guarda <b>no próprio disco</b>.
 *
 * <p>É o que o nó da infraestrutura própria faz ({@code NODE_LOCAL}, que cobre
 * {@code OBJECT_PUT}): ele não é um participante que cede espaço alheio, ela é dona do lugar
 * onde o objeto mora. O Control Plane é quem decide <b>o que</b> este nó guarda — a colocação no
 * catálogo — e aqui só se materializa.</p>
 *
 * <h2>Por que um componente e não uma coluna</h2>
 * <p>O catálogo já diz que existe cópia {@code OK} num nó. O que faltava era o arquivo existindo
 * de fato naquele nó. Gravar num diretório próprio, com a mesma raiz de storage, evita inventar
 * um segundo modelo: o caminho é derivado do {@code objectId} lógico, não de um id de arquivo.</p>
 *
 * <h2>Desligado por padrão</h2>
 * <p>{@code rede.storage.node-body-root} vazio = este processo não guarda corpo de nó (é o caso
 * do Control Plane puro e da suíte). Ligado, é o nó local que também atende requisições: o
 * arquivo fica em disco e é servido dele, sem passar pelo Control Plane.</p>
 */
@Component
@ConditionalOnProperty(name = "rede.storage.node-body-root")
public class NodeBodyStore {

	private static final Logger LOG = LoggerFactory.getLogger(NodeBodyStore.class);

	/** Mesmo teto de nome do catálogo: o id é gerado por nós, não vem do cliente. */
	private static final int MAX_ID_LENGTH = 64;

	private final Path root;

	NodeBodyStore(@Value("${rede.storage.node-body-root}") String root) {
		this.root = Path.of(root).toAbsolutePath().normalize();
		try {
			Files.createDirectories(this.root);
		}
		catch (IOException unwritable) {
			// Falhar no boot, e não na primeira réplica: um nó que declara guardar corpo e não
			// consegue criar o diretório falharia em toda tarefa, uma por uma, sem ninguém ver.
			throw new IllegalStateException("Nao foi possivel criar o diretorio de corpos do no: " + this.root,
				unwritable);
		}
	}

	/** Onde o corpo deste objeto mora. */
	public Path pathOf(String objectId) {
		if (objectId == null || objectId.isBlank() || objectId.length() > MAX_ID_LENGTH
				|| !objectId.matches("[A-Za-z0-9_-]+")) {
			// O id vem do catálogo, mas validar aqui impede que um bug de geração vire escrita
			// fora do diretório (`..` no id atravessaria a raiz).
			throw new IllegalArgumentException("objectId invalido para corpo local: " + objectId);
		}
		return root.resolve(objectId + ".bin");
	}

	/** Este nó já tem o corpo deste objeto? */
	public boolean has(String objectId) {
		return Files.isRegularFile(pathOf(objectId));
	}

	/**
	 * Grava o corpo de forma atômica: o arquivo só aparece com o nome final depois de inteiro no
	 * disco. Um processo morto no meio deixa o temporário, nunca um corpo pela metade que o nó
	 * serviria como se fosse a cópia.
	 *
	 * @return quantos bytes foram gravados
	 */
	public long write(String objectId, InputStream body) throws IOException {
		Path destination = pathOf(objectId);
		Path temp = Files.createTempFile(root, objectId + ".", ".partial");
		try {
			long bytes = Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
			// Mesmo diretório: o move é rename atômico, sem cópia entre sistemas de arquivos.
			Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return bytes;
		}
		catch (IOException failed) {
			Files.deleteIfExists(temp);
			throw failed;
		}
	}

	/** Abre o corpo gravado. */
	public InputStream read(String objectId) throws IOException {
		return Files.newInputStream(pathOf(objectId));
	}

	/** Bytes que os corpos ocupam neste disco — o que o heartbeat reporta como livro medido. */
	public long usedBytes() {
		try (var stream = Files.list(root)) {
			return stream.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".bin"))
				.mapToLong(NodeBodyStore::sizeOf)
				.sum();
		}
		catch (IOException unreadable) {
			LOG.warn("Nao foi possivel medir o diretorio de corpos {}: {}", root, unreadable.getMessage());
			return 0;
		}
	}

	/**
	 * Apaga o corpo de um objeto que o Control Plane disse não estar mais aqui. Só se chama com a
	 * colocação do nó já removida no catálogo: apagar antes deixaria o catálogo prometendo uma
	 * cópia que não existe.
	 */
	public boolean remove(String objectId) {
		try {
			return Files.deleteIfExists(pathOf(objectId));
		}
		catch (IOException failed) {
			LOG.warn("Falha ao remover corpo {}: {}", objectId, failed.getMessage());
			return false;
		}
	}

	private static long sizeOf(Path path) {
		try {
			return Files.size(path);
		}
		catch (IOException unreadable) {
			return 0;
		}
	}
}
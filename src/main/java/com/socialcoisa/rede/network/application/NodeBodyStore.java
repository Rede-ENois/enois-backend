package com.socialcoisa.rede.network.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.lang.Nullable;
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
@Conditional(NodeBodyRootConfigured.class)
public class NodeBodyStore {

	private static final Logger LOG = LoggerFactory.getLogger(NodeBodyStore.class);

	/** Mesmo teto de nome do catálogo: o id é gerado por nós, não vem do cliente. */
	private static final int MAX_ID_LENGTH = 64;

	private final Path root;
	/** Nulo = instalação sem chave: grava em claro, e o painel diz que não há cifra em repouso. */
	private final EncryptedBodyFile cipher;

	/**
	 * Construtor do Spring: a chave de cifra é a mesma de {@code REDE_SECURITY_ENCRYPTION_KEY}.
	 *
	 * <p>Chave inválida (não base64, não 32 bytes) <b>derruba o boot</b>: o núcleo da aplicação já a
	 * exige, e a única forma de chegar aqui com chave ruim é configuração quebrada. Degradar para
	 * "grava em claro" em silêncio seria dizer que o disco está cifrado quando não está.</p>
	 */
	@org.springframework.beans.factory.annotation.Autowired
	NodeBodyStore(@Value("${rede.storage.node-body-root}") String root,
			@Value("${rede.security.encryption-key:}") String encryptionKey) {
		this(root, decodeKey(encryptionKey));
	}

	/** Construtor sem cifra (testes e instalação sem chave). */
	NodeBodyStore(String root) {
		this(root, (byte[]) null);
	}

	/** Com cifra explícita (testes do disco cifrado). */
	NodeBodyStore(String root, byte[] encryptionKey) {
		if (root == null || root.isBlank()) {
			// `@ConditionalOnProperty` casa com a propriedade PRESENTE, mesmo vazia. Sem esta
			// recusa, `Path.of("")` resolveria para o diretório corrente e o processo gravaria
			// corpo de nó no diretório de trabalho — desligado tem de ser ausente, não vazio.
			throw new IllegalStateException(
				"rede.storage.node-body-root vazio: omita a propriedade para nao guardar corpo de no");
		}
		this.root = Path.of(root).toAbsolutePath().normalize();
		this.cipher = encryptionKey == null ? null : new EncryptedBodyFile(encryptionKey);
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
			if (cipher != null) {
				// Cifra num segundo temporário: o corpo em claro só existe no temporário do
				// diretório, e o arquivo final já nasce cifrado (§10, "retirada física do disco").
				Path encrypted = Files.createTempFile(root, objectId + ".", ".encpartial");
				try {
					cipher.write(temp, encrypted, root);
				}
				catch (IOException falha) {
					Files.deleteIfExists(encrypted);
					throw falha;
				}
				Files.deleteIfExists(temp);
				temp = encrypted;
			}
			// Mesmo diretório: o move é rename atômico, sem cópia entre sistemas de arquivos.
			Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return bytes;
		}
		catch (IOException failed) {
			Files.deleteIfExists(temp);
			throw failed;
		}
	}

	/**
	 * Abre o corpo gravado.
	 *
	 * <p>Corpo no formato cifrado é decifrado em fluxo; corpo legado (gravado antes da chave) é
	 * servido como está. Os dois coexistem no mesmo diretório — recusar o antigo reprovaria todas as
	 * réplicas que já existiam no dia em que a chave foi configurada.</p>
	 */
	public InputStream read(String objectId) throws IOException {
		Path path = pathOf(objectId);
		if (cipher != null && cipher.isEncrypted(path)) {
			return cipher.read(path);
		}
		return EncryptedBodyFile.plain(path);
	}

	/**
	 * Quantos bytes o objeto tem <b>em claro</b> no disco desta réplica.
	 *
	 * <p>É o número que o catálogo promete, então é ele que a leitura confere — sem percorrer o fluxo
	 * e sem trazer o objeto para a memória. Corpo legado (em claro, sem cabeçalho) tem o próprio
	 * tamanho do arquivo; corpo cifrado desconta o cabeçalho do {@code ENOISENC1} e a tag do GCM.</p>
	 */
	public long plainBytesOf(String objectId) throws IOException {
		Path path = pathOf(objectId);
		long noDisco = Files.size(path);
		if (cipher != null && cipher.isEncrypted(path)) {
			return noDisco - EncryptedBodyFile.headerBytes() - 16;
		}
		return noDisco;
	}

	/** O disco está guardando corpo cifrado nesta instalação? O painel lê isto. */
	public boolean encryptionAtRest() {
		return cipher != null;
	}

	private static byte[] decodeKey(String base64) {
		if (base64 == null || base64.isBlank()) {
			return null;
		}
		try {
			byte[] bytes = java.util.Base64.getDecoder().decode(base64.trim());
			if (bytes.length != 32) {
				throw new IllegalStateException(
					"REDE_SECURITY_ENCRYPTION_KEY precisa ter 32 bytes (chave AES-256) para cifrar corpo de no.");
			}
			return bytes;
		}
		catch (IllegalArgumentException falha) {
			throw new IllegalStateException("REDE_SECURITY_ENCRYPTION_KEY nao e base64 valido.", falha);
		}
	}

	/**
	 * Os ids dos corpos que este disco tem.
	 *
	 * <p>É o outro lado do catálogo: o catálogo diz o que este nó <b>deveria</b> guardar, e isto
	 * diz o que ele <b>de fato</b> tem. Só {@code .bin} entra: temporário de escrita interrompida
	 * não é corpo e não pode virar "órfão" — apagá-lo no inventário esconderia uma tarefa que está
	 * rodando agora.</p>
	 */
	public java.util.Set<String> objectIds() {
		try (var stream = Files.list(root)) {
			return stream.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(nome -> nome.endsWith(".bin"))
				.map(nome -> nome.substring(0, nome.length() - ".bin".length()))
				.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
		}
		catch (IOException unreadable) {
			// Não listar não é "não tenho nada": vazio aqui faria o inventário dizer que o disco
			// está limpo e que todas as cópias sumiram de uma vez.
			throw new IllegalStateException("Nao foi possivel listar os corpos em " + root, unreadable);
		}
	}

	/**
	 * De quem é este disco, lendo as mesmas credenciais que o runtime grava no diretório do nó
	 * ({@code node.json} com o id, {@code api-key} com a chave).
	 *
	 * <p>Uma propriedade separada ({@code rede.storage.node-id}) daria dois lugares dizendo de quem
	 * é o disco, e o segundo é o que ninguém atualiza. Lendo o que já existe, o processo que serve as
	 * rotas de Storage é <b>o mesmo</b> nó que o runtime provisionou.</p>
	 *
	 * <p>{@code api-key} é exigida: {@code node.json} sozinho não prova que este processo é aquele
	 * nó. E ela fica em {@code 0600} — um arquivo de credencial legível por outro usuário é o
	 * próximo incidente.</p>
	 *
	 * @return o id do nó dono, ou {@code null} se este diretório não é de um nó provisionado
	 */
	@Nullable
	public String ownerNodeId() {
		return resolveOwnerNodeId();
	}

	@Nullable
	private String resolveOwnerNodeId() {
		// O runtime grava `node.json` e `api-key` em $NODE_HOME e os corpos em $NODE_HOME/objects.
		// Quando o root é o diretório de corpos, as credenciais estão no pai; quando o root já é o
		// $NODE_HOME, estão nele. Nos dois casos a fonte é a mesma que o nó provisionado usa.
		Path home = root.getFileName() != null && root.getFileName().toString().equals("objects")
			? root.getParent()
			: root;
		Path meta = home == null ? null : home.resolve("node.json");
		Path chave = home == null ? null : home.resolve("api-key");
		if (meta == null || !Files.isRegularFile(meta) || chave == null || !Files.isRegularFile(chave)) {
			return null;
		}
		if (!ownCredentials(chave)) {
			LOG.warn("api-key de {} legivel por outros usuarios; o no nao e assumido", home);
			return null;
		}
		try {
			tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(meta.toFile());
			tools.jackson.databind.JsonNode id = json.get("nodeId");
			return id == null || !id.asString().matches("[A-Za-z0-9_-]{1,64}") ? null : id.asString();
		}
		catch (RuntimeException ilegivel) {
			LOG.warn("node.json ilegivel em {}: {}", root.getParent(), ilegivel.getMessage());
			return null;
		}
	}

	/** O grupo ou outros não podem ler a credencial. Sem POSIX (Windows) a checagem não se aplica. */
	private static boolean ownCredentials(Path chave) {
		try {
			var perms = Files.getPosixFilePermissions(chave);
			return perms.stream().noneMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_"));
		}
		catch (UnsupportedOperationException semPosix) {
			return true;
		}
		catch (IOException ilegivel) {
			return false;
		}
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
package com.socialcoisa.rede.network.application;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.socialcoisa.rede.network.application.port.out.NetworkAuthorityRepository;
import com.socialcoisa.rede.network.application.port.out.NetworkNodeRepository;
import com.socialcoisa.rede.network.domain.NetworkNode;
import com.socialcoisa.rede.network.domain.TaskType;

/**
 * A configuração que o nó lê depois de aprovado: perfis, limites e tarefas permitidas — assinada
 * quando a chave está configurada.
 *
 * <p>É a regra de ouro do modelo de identidade em forma de resposta: <b>permissão é dado do Control
 * Plane, e o nó só lê</b>. O runtime não decide o que pode executar; ele confere a assinatura e
 * obedece, e uma config não assinada (ou com assinatura que não confere) é recusada por ele, não
 * por nós.</p>
 *
 * <p><b>Ler a config duas vezes sem nada mudar devolve o mesmo documento.</b> {@code configVersion} e
 * {@code issuedAt} vêm do ciclo de vida do nó (V284), não do relógio da requisição: o runtime trata
 * config nova como evento e revalida a cada documento diferente, então uma config "nova" a cada
 * segundo viraria ruído e esconderia a mudança que importa.</p>
 *
 * <p>Ler a própria config <b>não exige</b> o nó estar operável: o enroll termina em
 * {@code PENDING_APPROVAL} e o runtime precisa receber de volta a razão. Por isso o serviço recebe
 * um nó que já passou pela autenticação da chave e devolve {@code operable=false} em vez de 403 —
 * recusar aqui deixaria o instalador sem resposta justamente no passo em que ele está esperando
 * aprovação humana.</p>
 */
@Service
public class NetworkConfigService {

	private static final Logger LOG = LoggerFactory.getLogger(NetworkConfigService.class);

	/**
	 * Versão do <b>formato</b> do documento. Sobe quando o formato muda de um jeito que o runtime
	 * antigo não entende; a versão da <b>permissão</b> é outra coisa e vem do nó ({@code configVersion}).
	 *
	 * <p>2 (fase 6): {@code term} entrou no payload e na linha canônica — o nó passa a recusar config
	 * de termo menor que o último visto (doc 06 §4, regra 2). Runtime do formato 1 não leria o termo;
	 * runtime do formato 2 reconstrói a linha com ele.</p>
	 *
	 * <p>3 (fase 6): {@code ttlSeconds} entrou no payload e na linha canônica — a config passa a
	 * <b>vencer</b>. Sem prazo assinado, "Control Plane fora" e "Control Plane dizendo que tudo está
	 * bem" eram indistinguíveis para o nó, que seguia com a última permissão para sempre (doc 06 §6).</p>
	 *
	 * <p>4 (fase 5): {@code allowedRoutes} entrou no payload e na linha canônica — a lista de rotas
	 * (pares {@code nome=url}) que o nó pode chamar. A rota é a <b>permissão</b>, e permissão que não
	 * está na assinatura é permissão que qualquer intermediário pode reescrever: o nó obedeceria a
	 * um destino que ninguém autorizou. Assinar a lista é o que fecha o SSRF (doc 02, linha SSRF) —
	 * sem isso, a task {@code API_REQUEST} seria uma porta aberta para dentro da rede do
	 * participante.</p>
	 */
	static final int FORMAT_VERSION = 4;

	private final Clock clock;
	private final NetworkNodeRepository nodes;
	private final NetworkAuthorityRepository authorities;
	private final Optional<PrivateKey> signingKey;
	private final Optional<PublicKey> pinningKey;
	private final int ttlSeconds;
	/** Rotas que o nó pode chamar, no formato {@code nome=url}. Vazio por padrão: sem rota declarada, o
	 * nó não tem para onde ir. */
	private final List<String> allowedRoutes;

	NetworkConfigService(Clock clock, NetworkNodeRepository nodes, NetworkAuthorityRepository authorities,
			@Value("${rede.network.config.signing-key:}") String signingKey,
			@Value("${rede.network.config.signing-public-key:}") String publicKey,
			@Value("${rede.network.config.ttl-seconds:300}") int ttlSeconds,
			@Value("${rede.network.config.allowed-routes:}") String allowedRoutes) {
		this.clock = clock;
		this.nodes = nodes;
		this.authorities = authorities;
		this.signingKey = NetworkConfigSigner.privateKey(signingKey);
		this.pinningKey = NetworkConfigSigner.publicKey(publicKey);
		this.allowedRoutes = parseRoutes(allowedRoutes);
		// TTL zero ou negativo venceria a config no mesmo instante em que ela nasce: o nó recusaria
		// operar sem nenhuma pista de por quê. Um piso explícito transforma config errada em aviso.
		if (ttlSeconds <= 0) {
			LOG.warn("rede.network.config.ttl-seconds={} invalido; usando 300s", ttlSeconds);
			this.ttlSeconds = 300;
		}
		else {
			this.ttlSeconds = ttlSeconds;
		}
		if (signingKey != null && !signingKey.isBlank() && this.signingKey.isEmpty()) {
			// Chave presente e inválida é configuração quebrada: o nó receberia config sem assinatura e
			// recusaria operar. Melhor gritar no log do que descobrir no install.
			LOG.warn("rede.network.config.signing-key presente mas nao e uma chave Ed25519 PKCS#8 valida");
		}
		if (pinningKey.isPresent() && signingKey != null && !signingKey.isBlank()) {
			// O pino vai no payload para o runtime gravar no install. Servir um pino que não é o par da
			// chave de assinatura faria o nó recusar toda config — e a causa ficaria a mil km daqui.
			LOG.info("Config de no assinada com Ed25519 e chave publica de pinagem publicada");
		}
	}

	/**
	 * Lê a propriedade de rotas. Cada entrada é {@code nome=url}; entradas sem {@code =}, com
	 * nome/URL vazios, com nome fora do conjunto aceito pelo runtime ou com destino que não é
	 * http(s) são descartadas com aviso — uma rota malformada que entrasse na assinatura
	 * seria um destino que o runtime não conseguiria resolver, e o nó falharia no meio do
	 * trabalho.
	 *
	 * <p>O nome tem de ser o mesmo conjunto que o runtime valida no payload
	 * ({@code [A-Za-z0-9._-]+}): um nome com vírgula dentro seria ambíguo na hora de separar as
	 * entradas e, pior, o runtime não conseguiria resolver o nome — a rota entraria assinada e
	 * morreria como "não autorizada" no meio do trabalho.</p>
	 *
	 * <p>A vírgula é separador de entradas <b>só quando começa uma entrada nova</b> (isto é, quando
	 * o que vem depois dela é {@code nome=url} válido). A vírgula é caractere legítimo de URL (query
	 * string), e um separador que não distingue as duas cortaria o destino no meio — o nó chamaria
	 * um caminho diferente do declarado. Quem precisar de uma vírgula literal na query escreve
	 * {@code %2C}: é a forma URL canônica e não deixa a ambiguidade para trás.</p>
	 *
	 * <p>A lista sai ordenada e sem repetição: a ordem do arquivo de configuração não é contrato, e
	 * duas ordens distintas para o mesmo conjunto de rotas dariam assinaturas diferentes para a
	 * mesma permissão.</p>
	 */
	private static List<String> parseRoutes(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		// Indexada pelo nome: o runtime resolve por nome, e duas rotas com o mesmo nome
		// significam um destino que nunca será chamado — sem que ninguém perceba. A
		// repetição exata é a mesma rota e some sem aviso.
		var byName = new java.util.LinkedHashMap<String, String>();
		for (String entry : splitRouteEntries(raw)) {
			String trimmed = entry.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			int separator = trimmed.indexOf('=');
			if (separator <= 0 || separator == trimmed.length() - 1) {
				LOG.warn("rota ignorada (esperado nome=url): {}", trimmed);
				continue;
			}
			String name = trimmed.substring(0, separator);
			String url = trimmed.substring(separator + 1);
			if (!isRouteName(name)) {
				LOG.warn("rota ignorada (nome fora do conjunto aceito pelo runtime): {}", trimmed);
				continue;
			}
			if (!url.startsWith("http://") && !url.startsWith("https://")) {
				LOG.warn("rota ignorada (destino não é http(s)): {}", trimmed);
				continue;
			}
			if (hasInvalidRouteChar(url)) {
				// Três caracteres quebram a leitura da lista no runtime: " e \ viram escape
				// no JSON (e a quebra dos itens, pelo trecho ""," no shell, teria mais de um
				// ponto de corte), e ] é o fecho do array para o regex que recorta a lista.
				// Um destino que os trouxesse entraria assinado e o nó recusaria a config
				// inteira — ou leria a rota em pedaços. Nenhum deles é caractere de URL sem
				// escape: quem precisar escreve %22, %5C e %5D.
				//
				// Além disso, só ASCII imprimível: a lista é ordenada no signer (ordem
				// natural de String, que é por unidade de código UTF-16) e no runtime (sort
				// por byte UTF-8), e as duas ordens divergem fora do ASCII — a linha
				// reconstruída pelo nó deixaria de conferir com a assinatura.
				LOG.warn("rota ignorada (destino fora do conjunto aceito): {}", trimmed);
				continue;
			}
			String previous = byName.putIfAbsent(name, trimmed);
			if (previous != null && !previous.equals(trimmed)) {
				LOG.warn("rota ignorada (nome repetido com outro destino): {}", trimmed);
			}
		}
		// Ordenada: a ordem do arquivo de configuração não é contrato, e duas ordens
		// distintas para o mesmo conjunto dariam assinaturas diferentes para a mesma
		// permissão.
		return byName.values().stream().sorted().toList();
	}

	/**
	 * Separa as entradas da propriedade de rotas. Separadores: {@code ;}, quebra de linha e a
	 * vírgula que introduz uma entrada nova ({@code nome=url} válido). Uma vírgula que não
	 * introduz entrada nova pertence ao destino e fica nele.
	 */
	private static List<String> splitRouteEntries(String raw) {
		var entries = new java.util.ArrayList<String>();
		var current = new StringBuilder();
		for (int index = 0; index < raw.length(); index++) {
			char character = raw.charAt(index);
			if (character == ';' || character == '\n') {
				entries.add(current.toString());
				current.setLength(0);
				continue;
			}
			if (character == ',' && startsNewEntry(raw, index + 1)) {
				entries.add(current.toString());
				current.setLength(0);
				continue;
			}
			current.append(character);
		}
		entries.add(current.toString());
		return entries;
	}

	/** A partir de {@code position}, o texto começa uma entrada nova ({@code nome=url})? */
	private static boolean startsNewEntry(String raw, int position) {
		int cursor = position;
		while (cursor < raw.length() && Character.isWhitespace(raw.charAt(cursor))) {
			cursor++;
		}
		if (cursor >= raw.length()) {
			return false;
		}
		int nameEnd = cursor;
		while (nameEnd < raw.length() && isRouteNameChar(raw.charAt(nameEnd))) {
			nameEnd++;
		}		if (nameEnd == cursor || nameEnd >= raw.length() || raw.charAt(nameEnd) != '=') {
			return false;
		}
		String url = raw.substring(nameEnd + 1);
		return url.startsWith("http://") || url.startsWith("https://");
	}

	/**
	 * O nome da rota é um identificador, não texto livre: o mesmo conjunto que o runtime
	 * aceita ({@code [A-Za-z0-9._-]+}) e só ASCII. O ASCII não é preciosismo: o signer
	 * ordena a lista por unidade de código UTF-16 e o runtime ordena por byte UTF-8, e as
	 * duas ordens divergem fora do ASCII — a linha reconstruída pelo nó deixaria de
	 * conferir com a assinatura.
	 */
	private static boolean isRouteName(String name) {
		if (name.isEmpty()) {
			return false;
		}
		for (int index = 0; index < name.length(); index++) {
			if (!isRouteNameChar(name.charAt(index))) {
				return false;
			}
		}
		return true;
	}

	/** Caractere aceito no nome da rota: o mesmo conjunto que o runtime valida no payload. */
	private static boolean isRouteNameChar(char character) {
		return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
			|| (character >= '0' && character <= '9') || character == '.' || character == '_'
			|| character == '-';
	}

	/**
	 * O destino traz algum caractere que a leitura da lista no runtime não tolera?
	 *
	 * <p>{@code "} e {@code \} viram escape no JSON e {@code ]} fecha o array: os três
	 * fariam a lista ser lida em pedaços errados, e a rota poderia ser recortada — ou a
	 * config inteira recusada. Nenhum deles é caractere válido de URL sem escape: quem
	 * precisar escreve {@code %22}, {@code %5C} e {@code %5D}.</p>
	 *
	 * <p>Além disso, só ASCII imprimível (0x21 a 0x7E): o signer ordena por unidade de
	 * código UTF-16 e o runtime por byte UTF-8, e as duas ordens divergem fora do ASCII —
	 * a linha reconstruída pelo nó deixaria de conferir com a assinatura, e o nó recusaria
	 * uma config legítima.</p>
	 */
	private static boolean hasInvalidRouteChar(String url) {
		for (int index = 0; index < url.length(); index++) {
			char character = url.charAt(index);
			if (character < 0x21 || character > 0x7E) {
				return true;
			}
			if (character == '"' || character == '\\' || character == ']') {
				return true;
			}
		}
		return false;
	}

	/**
	 * A config do nó autenticado. {@code signed} diz a verdade sobre {@code signature}: sem chave,
	 * {@code false} e {@code null} — nunca {@code true} com campo vazio.
	 */
	public NodeConfigView forNode(NetworkNode node) {
		boolean operable = node.canOperate();
		List<String> profiles = node.profiles().stream().map(Enum::name).sorted().toList();
		// Nó fora de operação não recebe tarefa nenhuma: devolver a lista vazia e `operable=false` é
		// o mesmo critério que o scheduler usa (`accepts` exige `canOperate`), dito ao runtime.
		List<String> allowedTasks = operable
				? TaskType.allowedTypesFor(node.profiles()).stream().map(Enum::name).toList()
				: List.of();
		var stamp = nodes.configStamp(node.id())
			.orElseGet(() -> new NetworkNodeRepository.ConfigStamp(FORMAT_VERSION, Instant.now(clock)));
		var limits = new LimitsView(node.declaredCpuCores(), node.declaredMemoryMb(), node.declaredDiskMb(),
			node.declaredBandwidthMbps());
		// O termo vai no documento e na assinatura: é o que deixa a regra do nó ("termo só sobe")
		// verificável contra quem assinou, não contra o transporte.
		var authority = authorities.current()
			.orElseGet(() -> new NetworkAuthorityRepository.AuthorityRecord(0, null, null));
		var fields = new NetworkConfigSigner.SignedFields(FORMAT_VERSION, stamp.version(), node.id(), profiles,
			limits.cpuCores(), limits.memoryMb(), limits.diskMb(), limits.bandwidthMbps(), allowedTasks,
			allowedRoutes, stamp.issuedAt().toString(), ttlSeconds, authority.term());
		String signature = sign(fields.canonical());
		return new NodeConfigView(node.id(), node.status().name(), operable, FORMAT_VERSION, stamp.version(),
			profiles, limits, allowedTasks, allowedRoutes, node.region(), node.country(),
			stamp.issuedAt().toString(),
			signature != null, signature, signature == null ? null : NetworkConfigSigner.ALGORITHM,
			pinningKey.map(NetworkConfigService::encode).orElse(null), ttlSeconds, authority.term());
	}

	private String sign(String canonical) {
		PrivateKey key = signingKey.orElse(null);
		if (key == null) {
			return null;
		}
		try {
			Signature signer = Signature.getInstance(NetworkConfigSigner.ALGORITHM);
			signer.initSign(key);
			signer.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(signer.sign());
		}
		catch (Exception impossible) {
			// Assinar falhou com chave válida: não há como mandar config assinada nesta resposta, e
			// mandar sem dizer seria pior. A resposta sai `signed:false` e o runtime recusa.
			LOG.error("Falha ao assinar config do no {}", impossible.getClass().getSimpleName());
			return null;
		}
	}

	private static String encode(PublicKey key) {
		return Base64.getEncoder().encodeToString(key.getEncoded());
	}

	/**
	 * Os limites que o participante cedeu, ditos ao nó. {@code diskMb} é aplicado pelo runtime do
	 * nó, que é quem escreve no volume: teto do estado de conteúdo (borda e réplica), com a
	 * identidade do nó fora da conta. {@code bandwidthMbps} também é do runtime, que é quem
	 * transfere: teto de taxa ({@code --limit-rate}) das transferências de corpo. Nenhum dos dois
	 * entra no despacho — o Control Plane não mede banda nem disco de nó remoto.
	 */
	public record LimitsView(int cpuCores, int memoryMb, int diskMb, int bandwidthMbps) {
	}

	/**
	 * {@code publicKey} é o pino do Control Plane: vai junto para o runtime gravar logo depois do
	 * enroll e conferir toda config seguinte sem precisar confiar no transporte desta.
	 *
	 * <p>{@code formatVersion} e {@code configVersion} são coisas diferentes de propósito: o primeiro
	 * é o formato do documento (muda quando nós antigos não entenderiam), o segundo é versão da
	 * <b>permissão</b> (muda a cada transição administrativa). {@code term} é a terceira coisa
	 * diferente: é a versão da <b>autoridade</b> que assinou (fase 6) — só sobe na promoção, e o nó
	 * recusa documento de termo menor que o último que aceitou.</p>
	 *
	 * <p>{@code ttlSeconds} é há quanto tempo <b>esta</b> config deixa de valer, contado do instante em
	 * que o nó a recebeu — não uma data absoluta. Prazo absoluto faria a leitura depender do relógio de
	 * quem serve: relógio adiantado no Control Plane encurtaria a config de todo mundo, e atrasado a
	 * prolongaria. O nó mede com o próprio relógio, que é o único que ele pode conferir.</p>
	 *
	 * <p>{@code allowedRoutes} é a lista de rotas ({@code nome=url}) que o nó pode chamar, e ela vai
	 * <b>assinada</b>: destino que não está na assinatura não é destino autorizado. O runtime resolve o
	 * nome da rota contra esta lista e recusa qualquer outro — nunca segue uma URL vinda do
	 * payload (doc 03, contrato {@code API_REQUEST}).</p>
	 */
	public record NodeConfigView(String nodeId, String status, boolean operable, int formatVersion,
			int configVersion, List<String> profiles, LimitsView limits, List<String> allowedTasks,
			List<String> allowedRoutes, String region,
			String country, String issuedAt, boolean signed, String signature, String algorithm, String publicKey,
			int ttlSeconds, int term) {
	}
}

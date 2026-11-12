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
	 */
	static final int FORMAT_VERSION = 1;

	private final Clock clock;
	private final NetworkNodeRepository nodes;
	private final Optional<PrivateKey> signingKey;
	private final Optional<PublicKey> pinningKey;

	NetworkConfigService(Clock clock, NetworkNodeRepository nodes,
			@Value("${rede.network.config.signing-key:}") String signingKey,
			@Value("${rede.network.config.signing-public-key:}") String publicKey) {
		this.clock = clock;
		this.nodes = nodes;
		this.signingKey = NetworkConfigSigner.privateKey(signingKey);
		this.pinningKey = NetworkConfigSigner.publicKey(publicKey);
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
		var fields = new NetworkConfigSigner.SignedFields(FORMAT_VERSION, stamp.version(), node.id(), profiles,
			limits.cpuCores(), limits.memoryMb(), limits.diskMb(), limits.bandwidthMbps(), allowedTasks,
			stamp.issuedAt().toString());
		String signature = sign(fields.canonical());
		return new NodeConfigView(node.id(), node.status().name(), operable, FORMAT_VERSION, stamp.version(),
			profiles, limits, allowedTasks, node.region(), node.country(), stamp.issuedAt().toString(),
			signature != null, signature, signature == null ? null : NetworkConfigSigner.ALGORITHM,
			pinningKey.map(NetworkConfigService::encode).orElse(null));
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
	 * Os limites que o participante cedeu, ditos ao nó. Disco e banda ainda não têm consumidor no
	 * despacho — vão aqui para que o runtime, que é quem sabe medir, possa se autorregular.
	 */
	public record LimitsView(int cpuCores, int memoryMb, int diskMb, int bandwidthMbps) {
	}

	/**
	 * {@code publicKey} é o pino do Control Plane: vai junto para o runtime gravar logo depois do
	 * enroll e conferir toda config seguinte sem precisar confiar no transporte desta.
	 *
	 * <p>{@code formatVersion} e {@code configVersion} são coisas diferentes de propósito: o primeiro
	 * é o formato do documento (muda quando nós antigos não entenderiam), o segundo é versão da
	 * <b>permissão</b> (muda a cada transição administrativa).</p>
	 */
	public record NodeConfigView(String nodeId, String status, boolean operable, int formatVersion,
			int configVersion, List<String> profiles, LimitsView limits, List<String> allowedTasks, String region,
			String country, String issuedAt, boolean signed, String signature, String algorithm, String publicKey) {
	}
}

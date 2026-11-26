package com.socialcoisa.rede.figurinhas.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.emotes.domain.EmoteNamePolicy;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.figurinhas.application.port.out.FigurinhasComunidadeRepository;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidade;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidadeEvento;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaImagem;
import com.socialcoisa.rede.figurinhas.domain.FigurinhasCodigoRules;
import com.socialcoisa.rede.media.application.CreateMediaUploadCommand;
import com.socialcoisa.rede.media.application.ImageDecoding;
import com.socialcoisa.rede.media.application.MediaDimensionsService;
import com.socialcoisa.rede.media.application.MediaImageModerationService;
import com.socialcoisa.rede.media.application.MediaService;
import com.socialcoisa.rede.media.application.MediaUploadView;
import com.socialcoisa.rede.media.application.port.out.MediaAssetRepository;
import com.socialcoisa.rede.media.application.port.out.MediaObjectStore;
import com.socialcoisa.rede.media.domain.MediaAsset;
import com.socialcoisa.rede.profile.application.port.out.UserProfileRepository;
import com.socialcoisa.rede.profile.domain.UserProfile;
import com.socialcoisa.rede.shared.audit.AuditTrail;
import com.socialcoisa.rede.shared.application.CacheInvalidator;
import com.socialcoisa.rede.shared.security.UserRateLimiter;
import com.socialcoisa.rede.shared.text.PlainText;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Figurinhas da Comunidade: o cadastro e o catálogo do que cada pessoa envia.
 *
 * <p>Fluxo do cadastro em três passos, para o arquivo nunca ir ao storage antes de ser conferido:</p>
 * <ol>
 * <li>{@link #criarUpload} declara a intenção (tipo e tamanho conferidos) e devolve o endereço de
 *   escrita do último passo, com a imagem pendente;</li>
 * <li>{@link #enviarImagem} recebe os bytes, confere assinatura/dimensões/integridade, tira os
 *   metadados e só então grava no storage. Falhou alguma conferência, nada é gravado e o asset
 *   pendente vira remoção — não sobra arquivo órfão nem arquivo inválido no bucket;</li>
 * <li>{@link #publicar} grava a figurinha com o código, depois de reconferir que o código está
 *   livre (a chave única do banco é a última palavra, para duas requisições simultâneas).</li>
 * </ol>
 *
 * <p>O que a figurinha grava é o endereço da imagem no host do app ({@code /storage/...}), servido
 * em cache público e imutável, sem credencial de storage no navegador.</p>
 */
@Service
public class FigurinhasComunidadeService {

	/** Teto de figurinhas ativas por conta: é um catálogo comunitário, não um álbum pessoal. */
	public static final int MAX_ATIVAS_POR_AUTOR = 50;

	/** O cache do catálogo e o índice do texto são a mesma lista: os dois caem juntos. */
	public static final String CACHE_CATALOGO = "figurinhasComunidade";
	public static final String CACHE_INDICE = "figurinhasComunidadeIndice";

	private final FigurinhasComunidadeRepository figurinhas;
	private final MediaService mediaService;
	private final MediaAssetRepository assets;
	private final ObjectProvider<MediaObjectStore> store;
	private final MediaImageModerationService imageModeration;
	private final MediaDimensionsService dimensions;
	private final UserProfileRepository profiles;
	private final CacheInvalidator caches;
	private final AuditTrail audit;
	private final org.springframework.transaction.support.TransactionTemplate transactions;
	private final UserRateLimiter rateLimiter;
	private final FigurinhaEnvioPolicy envioPolicy;
	private final Clock clock;

	public FigurinhasComunidadeService(FigurinhasComunidadeRepository figurinhas, MediaService mediaService,
			MediaAssetRepository assets, ObjectProvider<MediaObjectStore> store,
			MediaImageModerationService imageModeration, MediaDimensionsService dimensions,
			UserProfileRepository profiles, CacheInvalidator caches, AuditTrail audit,
			org.springframework.transaction.support.TransactionTemplate transactions, UserRateLimiter rateLimiter,
			FigurinhaEnvioPolicy envioPolicy, Clock clock) {
		this.figurinhas = figurinhas;
		this.mediaService = mediaService;
		this.assets = assets;
		this.store = store;
		this.imageModeration = imageModeration;
		this.dimensions = dimensions;
		this.profiles = profiles;
		this.caches = caches;
		this.audit = audit;
		this.transactions = transactions;
		this.rateLimiter = rateLimiter;
		this.envioPolicy = envioPolicy;
		this.clock = clock;
	}

	// ---------------------------------------------------------------- cadastro

	/** Passo 1: intenção de envio; devolve o endereço de escrita onde {@link #enviarImagem} lê. */
	public Envio criarUpload(String autorId, String mimeType, long tamanho) {
		String mime = FigurinhaImagem.exigirDeclaracaoValida(mimeType, tamanho);
		// O teto de criação é contado ao PUBLICAR (é ali que a figurinha passa a existir); aqui só
		// a conta verificada, a reputação e o espaço no catálogo.
		exigirNomeConfirmado(autorId);
		exigirReputacao(autorId);
		exigirEspacoPara(autorId);
		MediaUploadView view = mediaService.createUpload(new CreateMediaUploadCommand(new UserId(autorId),
			FigurinhaImagem.nomeDoArquivo(mime), mime, tamanho, MediaService.FIGURINHA_PURPOSE));
		// O cliente escreve a imagem AQUI, e não no endereço assinado do bucket: é esta rota que
		// recebe os bytes para conferir antes de qualquer coisa chegar ao storage.
		return new Envio(view.id(), view.objectKey(), "/storage/" + view.objectKey(), Map.of());
	}

	/**
	 * Passo 2: os bytes da imagem. Confere tudo, limpa metadados e grava no storage. Qualquer recusa
	 * apaga o asset pendente (pedido de remoção) — o bucket não guarda o que não passou.
	 *
	 * <p>O pedido de remoção fica DENTRO da mesma transação: se o commit do pedido falhasse, o
	 * asset continuaria como pendente e valido para publicação — exatamente o que a recusa queria
	 * impedir.</p>
	 */
	public EnvioConcluido enviarImagem(String autorId, String mediaId, byte[] bytes) {
		return inTransaction(() -> conferirEGravar(autorId, mediaId, bytes));
	}

	private EnvioConcluido conferirEGravar(String autorId, String mediaId, byte[] bytes) {
		MediaAsset asset = exigirAssetPendenteDoAutor(autorId, mediaId);
		FigurinhaArquivoValidado.Validado validado;
		byte[] limpo;
		try {
			validado = FigurinhaArquivoValidado.conferir(bytes, asset.mimeType());
			// A mesma checagem de conteúdo das imagens públicas de post (nudez/sexualidade). O SVG
			// não passa por ela: é vetor, não bitmap — quem confere o vetor é o sanitizador, em
			// FigurinhaArquivoValidado.
			imageModeration.verifyBeforeStorage(validado.bytes(), validado.mimeType());
			limpo = mediaService.limparMetadados(validado.bytes(), validado.mimeType(), asset.objectKey());
		}
		catch (RuntimeException recusa) {
			mediaService.requestDeletion(asset, null);
			throw recusa;
		}
		gravarNoStorage(asset, limpo);
		dimensions.recordarDimensoes(mediaId, validado.largura(), validado.altura());
		return new EnvioConcluido(asset.id(), "/storage/" + asset.objectKey(), validado.largura(), validado.altura());
	}

	private void gravarNoStorage(MediaAsset asset, byte[] bytes) {
		MediaObjectStore storage = store.getIfAvailable();
		if (storage == null) {
			throw new ApiException(503, "FIGURINHAS_INDISPONIVEIS",
				"O envio de figurinhas está indisponível no momento. Tente novamente em instantes.");
		}
		try {
			// A imagem SÓ entra pelo storage quando o storage existe: sem ele não há arquivo conferido
			// gravado, e publicar pelo resto do caminho seria publicar um asset vazio.
			storage.write(asset.objectKey(), asset.mimeType(), bytes.length, new java.io.ByteArrayInputStream(bytes));
		}
		catch (RuntimeException falha) {
			throw new ApiException(502, "FIGURINHAS_ENVIO_FALHOU",
				"Não foi possível salvar a imagem agora. Tente novamente em instantes.", falha);
		}
	}

	/**
	 * Passo 3: grava a figurinha. O nome é obrigatório; o código é escolhido por quem cadastra.
	 * O código não pode existir entre as ativas, não pode ser de outra origem (`enois_`, `bttv_`...)
	 * e não pode remeter a conteúdo sexual — a mesma política dos nomes do catálogo do seletor.
	 */
	public FigurinhaComunidade publicar(String autorId, String mediaId, String codigo, String nome) {
		String codigoFinal = validarCodigo(codigo);
		String nomeFinal = PlainText.defuse(nome == null ? "" : nome.trim());
		if (nomeFinal.length() < FigurinhaComunidade.NOME_MINIMO) {
			throw new ApiException(400, "FIGURINHA_NOME_OBRIGATORIO", "Dê um nome para a figurinha.");
		}
		if (nomeFinal.length() > FigurinhaComunidade.NOME_MAXIMO) {
			throw new ApiException(400, "FIGURINHA_NOME_LONGO",
				"O nome pode ter no máximo " + FigurinhaComunidade.NOME_MAXIMO + " caracteres.");
		}
		if (EmoteNamePolicy.blocked(nomeFinal.replace(' ', '_')) || EmoteNamePolicy.blocked(codigoFinal)) {
			throw new ApiException(400, "FIGURINHA_NOME_NAO_PERMITIDO",
				"Este nome ou código não é permitido pelas Diretrizes.");
		}
		exigirNomeConfirmado(autorId);
		exigirReputacao(autorId);
		exigirEspacoPara(autorId);
		// O teto de criação é contado aqui, e não no passo 1: é aqui que a figurinha passa a existir.
		rateLimiter.require(autorId, UserRateLimiter.CREATE_FIGURINHA);
		Instant agora = Instant.now(clock);
		return inTransaction(() -> {
			EnvioImagem enviada = exigirImagemEnviada(autorId, mediaId);
			FigurinhaComunidade figurinha = FigurinhaComunidade.criar(novoId("fig"), codigoFinal, nomeFinal, autorId,
				enviada.mediaId(), enviada.imageUrl(), enviada.mimeType(), enviada.largura(), enviada.altura(),
				enviada.bytes(), agora);
			if (figurinhas.findAtivaByCodigo(codigoFinal).isPresent()) {
				throw codigoEmUso();
			}
			try {
				figurinhas.save(figurinha);
			}
			catch (DataIntegrityViolationException corrida) {
				// Duas pessoas publicaram o mesmo código ao mesmo tempo: o banco barrou a segunda.
				throw codigoEmUso();
			}
			figurinhas.saveEvento(FigurinhaComunidadeEvento.criar(novoId("figev"), figurinha.id(),
				FigurinhaComunidadeEvento.CRIADA, autorId, null, agora));
			caches.afterCommit(CACHE_CATALOGO, CACHE_INDICE);
			return figurinha;
		});
	}

	// ---------------------------------------------------------------- leitura

	public Página pagina(int page, int limit, String query) {
		int tamanho = Math.max(1, Math.min(limit, 100));
		int numero = Math.max(1, page);
		long total = figurinhas.count(query);
		int paginas = total == 0 ? 0 : (int) ((total + tamanho - 1) / tamanho);
		int offset = (numero - 1) * tamanho;
		List<FigurinhaComunidade> itens = offset >= total ? List.of() : figurinhas.page(query, offset, tamanho);
		return new Página(itens, numero, tamanho, total, paginas);
	}

	/**
	 * Índice do texto: `codigo -> endereço da imagem`, só das figurinhas ATIVAS. É o mapa que o
	 * compositor e o leitor consultam; figurinha desativada não entra, e o código que ela usava
	 * volta a ser texto nos posts antigos.
	 */
	public Map<String, String> indice(Collection<String> codigos) {
		Map<String, String> indice = new LinkedHashMap<>();
		if (codigos == null || codigos.isEmpty()) {
			return indice;
		}
		List<String> normalizados = codigos.stream().filter(StringUtils::hasText)
			.map(c -> removerDoisPontos(c.trim().toLowerCase(Locale.ROOT))).distinct().limit(500).toList();
		for (String codigo : normalizados) {
			figurinhas.findAtivaByCodigo(codigo)
				.ifPresent(figurinha -> indice.put(figurinha.codigo(), figurinha.imageUrl()));
		}
		return indice;
	}

	public Map<String, AutorView> autores(Collection<String> autorIds) {
		Map<String, AutorView> autores = new LinkedHashMap<>();
		if (autorIds == null || autorIds.isEmpty()) {
			return autores;
		}
		for (UserProfile perfil : profiles.findAllByIds(new ArrayList<>(new LinkedHashSet<>(autorIds)))) {
			autores.put(perfil.id(), new AutorView(perfil.id(), perfil.username(), perfil.displayWithNickname(),
				perfil.avatar()));
		}
		return autores;
	}

	/** Histórico da figurinha (cadastro e desativações), mais antigo primeiro. */
	public List<FigurinhaComunidadeEvento> historico(String figurinhaId) {
		return figurinhas.eventos(figurinhaId);
	}

	/** A figurinha pelo id (a autoria é o que a denúncia confirmada sanciona). */
	public Optional<FigurinhaComunidade> porId(String id) {
		return figurinhas.findById(id);
	}

	public List<String> ativosEntre(Collection<String> ids) {
		return figurinhas.ativosEntre(ids);
	}

	// ---------------------------------------------------------------- moderação

	/**
	 * Desativa a figurinha: sai do índice, da busca e do seletor (o cache cai no commit), o código
	 * fica livre e a linha continua gravada com o motivo — histórico para auditoria. Idempotente:
	 * desativar de novo devolve o estado atual sem gravar evento repetido.
	 */
	public FigurinhaComunidade desativar(String atorId, String figurinhaId, String motivo) {
		String motivoLimpo = motivo == null ? "" : motivo.trim();
		return inTransaction(() -> {
			FigurinhaComunidade atual = figurinhas.findById(figurinhaId)
				.orElseThrow(() -> new ApiException(404, "FIGURINHA_NAO_ENCONTRADA", "Figurinha não encontrada."));
			if (!atual.ativa()) {
				return atual;
			}
			Instant agora = Instant.now(clock);
			FigurinhaComunidade desativada = atual.desativar(atorId, motivoLimpo, agora);
			figurinhas.save(desativada);
			figurinhas.saveEvento(FigurinhaComunidadeEvento.criar(novoId("figev"), desativada.id(),
				FigurinhaComunidadeEvento.DESATIVADA, atorId, motivoLimpo, agora));
			caches.afterCommit(CACHE_CATALOGO, CACHE_INDICE);
			audit.record(atorId, "FIGURINHA_COMUNIDADE_DESATIVADA", desativada.id(),
				"{\"autor\":\"" + escapar(desativada.autorId()) + "\",\"codigo\":\"" + escapar(desativada.codigo())
					+ "\",\"motivo\":\"" + escapar(motivoLimpo) + "\"}");
			return desativada;
		});
	}

	/** O autor tira a própria figurinha: mesma desativação, com o motivo registrado. */
	public FigurinhaComunidade removerPropria(String autorId, String figurinhaId) {
		FigurinhaComunidade figurinha = figurinhas.findById(figurinhaId)
			.orElseThrow(() -> new ApiException(404, "FIGURINHA_NAO_ENCONTRADA", "Figurinha não encontrada."));
		if (!figurinha.pertenceA(autorId)) {
			throw new ApiException(403, "FIGURINHA_NAO_E_SUA", "Esta figurinha não é sua.");
		}
		return desativar(autorId, figurinhaId, "Removida pelo autor");
	}

	// ---------------------------------------------------------------- auxiliares

	/**
	 * Roda o bloco numa transação própria: gravação no bucket só depois do que é preciso, e o
	 * pedido de remoção de um envio recusado só vale com o commit dele.
	 */
	private <T> T inTransaction(java.util.function.Supplier<T> bloco) {
		return transactions.execute(status -> bloco.get());
	}

	private static String validarCodigo(String codigo) {
		// O que o cliente grava é ":codigo:" — os dois-pontos são da sintaxe do texto, não do código.
		String bruto = removerDoisPontos(codigo == null ? "" : codigo.trim().toLowerCase(Locale.ROOT));
		if (!FigurinhasCodigoRules.formatoValido(bruto)) {
			throw new ApiException(400, "FIGURINHA_CODIGO_INVALIDO",
				"O código deve ter de 2 a 32 letras minúsculas, números, - ou _.");
		}
		if (FigurinhasCodigoRules.origemReservada(bruto)) {
			throw new ApiException(409, "FIGURINHA_CODIGO_RESERVADO",
				"Este código pertence a outra coleção de figurinhas. Escolha outro.");
		}
		return bruto;
	}

	private static String removerDoisPontos(String codigo) {
		if (codigo.length() > 2 && codigo.startsWith(":") && codigo.endsWith(":")) {
			return codigo.substring(1, codigo.length() - 1);
		}
		return codigo;
	}

	private void exigirNomeConfirmado(String autorId) {
		UserProfile perfil = profiles.findById(autorId)
			.orElseThrow(() -> new ApiException(401, "UNAUTHENTICATED", "Usuário autenticado ausente."));
		if (!perfil.identityVerified()) {
			throw new ApiException(403, "EMAIL_NOT_VERIFIED",
				"Confirme seu e-mail ou ative a verificação em duas etapas para cadastrar figurinhas.");
		}
	}

	/** O piso de reputação e o motivo da recusa vivem em {@link FigurinhaEnvioPolicy}. */
	private void exigirReputacao(String autorId) {
		envioPolicy.exigir(autorId);
	}

	/**
	 * Se a conta pode enviar figurinha, com números: a tela mostra o que falta antes de a pessoa
	 * escolher o arquivo, em vez de descobrir na recusa do último passo.
	 */
	public FigurinhaEnvioPolicy.Decisao decisaoDeEnvio(String autorId) {
		return envioPolicy.avaliar(autorId);
	}

	private void exigirEspacoPara(String autorId) {
		if (figurinhas.countAtivasDoAutor(autorId) >= MAX_ATIVAS_POR_AUTOR) {
			throw new ApiException(409, "FIGURINHA_LIMITE_ATINGIDO",
				"Você já tem " + MAX_ATIVAS_POR_AUTOR + " figurinhas ativas. Remova uma para cadastrar outra.");
		}
	}

	private MediaAsset exigirAssetPendenteDoAutor(String autorId, String mediaId) {
		MediaAsset asset = assets.findById(mediaId)
			.orElseThrow(() -> new ApiException(404, "FIGURINHA_ENVIO_NAO_ENCONTRADO", "Envio não encontrado."));
		if (!asset.ownerId().value().equals(autorId)) {
			throw new ApiException(403, "FIGURINHA_ENVIO_NAO_E_SEU", "Este envio não é seu.");
		}
		if (!MediaService.FIGURINHA_PURPOSE.equals(asset.purpose())) {
			throw new ApiException(400, "FIGURINHA_ENVIO_INVALIDO", "Este envio não é uma figurinha.");
		}
		if (!FigurinhaImagem.mimeAceito(asset.mimeType())) {
			throw new ApiException(400, "FIGURINHA_TIPO_NAO_PERMITIDO", "Envie a figurinha em PNG ou JPG.");
		}
		return asset;
	}

	/**
	 * A imagem que o passo 2 gravou. O registro do envio (dimensões medidas ali) é o que atesta que
	 * a imagem entrou pelo caminho conferido — publicar um asset que nunca teve os bytes conferidos
	 * é recusado.
	 */
	private EnvioImagem exigirImagemEnviada(String autorId, String mediaId) {
		MediaAsset asset = exigirAssetPendenteDoAutor(autorId, mediaId);
		ImageDecoding.Dimensions tamanho = dimensions.dimensoesEnviadas(mediaId);
		if (tamanho == null) {
			throw new ApiException(409, "FIGURINHA_IMAGEM_NAO_ENVIADA",
				"Envie a imagem da figurinha antes de publicá-la.");
		}
		FigurinhaImagem.exigirDimensoes(tamanho.width(), tamanho.height());
		return new EnvioImagem(asset.id(), "/storage/" + asset.objectKey(), asset.mimeType(), tamanho.width(),
			tamanho.height(), (int) asset.size());
	}

	private record EnvioImagem(String mediaId, String imageUrl, String mimeType, int largura, int altura, int bytes) {
	}

	private static ApiException codigoEmUso() {
		return new ApiException(409, "FIGURINHA_CODIGO_EM_USO", "Este código já é de outra figurinha.");
	}

	private static String escapar(String valor) {
		return valor == null ? "" : valor.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static String novoId(String prefixo) {
		return prefixo + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	/** Endereço de escrita devolvido no passo 1 (o mesmo contrato do upload de mídia). */
	public record Envio(String mediaId, String objectKey, String uploadUrl, Map<String, String> headers) {
	}

	public record EnvioConcluido(String mediaId, String imageUrl, int largura, int altura) {
	}

	public record AutorView(String id, String username, String nome, String avatarUrl) {
	}

	public record Página(List<FigurinhaComunidade> itens, int page, int limit, long total, int totalPages) {
	}
}

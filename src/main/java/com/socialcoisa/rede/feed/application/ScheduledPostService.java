package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.out.ScheduledPostRepository;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.feed.domain.ScheduledPost;
import com.socialcoisa.rede.feed.domain.UserId;
import com.socialcoisa.rede.feed.application.port.in.PublishPostUseCase;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * Agendamento de publicações. O post agendado NÃO é publicado em estado invisível: ele fica numa
 * tabela separada e só vira post de verdade quando o worker chama o MESMO caso de uso do composer
 * — então todas as regras (conteúdo, antiflood, conta restrita, notificações, menções) valem no
 * momento em que ele entra no ar, e uma recusa fica visível para o autor em `lastError`.
 */
@Service
public class ScheduledPostService {

	private static final Logger LOG = LoggerFactory.getLogger(ScheduledPostService.class);

	/** Margem mínima: tempo de o usuário perceber que agendou errado. */
	public static final Duration MIN_AHEAD = Duration.ofMinutes(1);
	public static final Duration MAX_AHEAD = Duration.ofDays(365);
	/** Depois de 3 recusas o item para de ser tentado (o autor decide o que fazer). */
	private static final int MAX_ATTEMPTS = 3;
	private static final int MAX_PENDING = 50;

	private final ScheduledPostRepository repository;
	private final PublishPostUseCase publishPost;
	private final Clock clock;

	private final com.socialcoisa.rede.coin.application.CoinService coins;

	public ScheduledPostService(ScheduledPostRepository repository, PublishPostUseCase publishPost, Clock clock,
			com.socialcoisa.rede.coin.application.CoinService coins) {
		this.coins = coins;
		this.repository = repository;
		this.publishPost = publishPost;
		this.clock = clock;
	}

	public record ScheduleCommand(PostType type, String content, String pageHtml, String articleBody,
			List<String> tags, List<String> mediaIds, List<String> mentionedUserIds, String communityId,
			String topic, Instant scheduledAt) {
	}

	@Transactional
	public ScheduledPost schedule(String userId, ScheduleCommand command) {
		if (command.type() == null) {
			throw throwInvalid("TIPO_OBRIGATORIO", "Escolha o tipo da publicação.");
		}
		PostType type = command.type();
		Instant scheduledAt = command.scheduledAt();
		Instant now = clock.instant();
		if (scheduledAt == null) {
			throw throwInvalid("DATA_OBRIGATORIA", "Escolha a data e a hora da publicação.");
		}
		if (scheduledAt.isBefore(now.plus(MIN_AHEAD))) {
			throw throwInvalid("DATA_NO_PASSADO", "Escolha um horário pelo menos 1 minuto à frente.");
		}
		if (scheduledAt.isAfter(now.plus(MAX_AHEAD))) {
			throw throwInvalid("DATA_MUITO_DISTANTE", "O agendamento vai até 1 ano à frente.");
		}
		// Mesma régua mínima do composer: cada tipo precisa do seu conteúdo (as demais
		// validações — tamanho, antiflood, mídia — rodam na publicação, pelo caso de uso).
		boolean temConteudo = switch (type) {
			case PAGE -> StringUtils.hasText(command.pageHtml());
			case ARTICLE -> StringUtils.hasText(command.content()) && StringUtils.hasText(command.articleBody());
			default -> StringUtils.hasText(command.content());
		};
		if (!temConteudo) {
			throw throwInvalid("CONTEUDO_OBRIGATORIO", "O post agendado não tem conteúdo.");
		}
		if (repository.findByUser(userId).size() >= MAX_PENDING) {
			throw new ApiException(409, "TOO_MANY_SCHEDULED",
				"Você já tem " + MAX_PENDING + " posts agendados. Cancele algum para criar outro.");
		}
		// Mesma regra da publicação direta, aplicada cedo para o erro aparecer no agendamento:
		// post de comunidade herda o assunto dela; post avulso declara um da taxonomia (ou nenhum).
		String topic;
		if (StringUtils.hasText(command.communityId()) && StringUtils.hasText(command.topic())) {
			throw throwInvalid("TOPIC_FROM_COMMUNITY", "Post em comunidade herda o assunto dela.");
		}
		if (StringUtils.hasText(command.topic())) {
			String declared = command.topic().trim().toUpperCase(java.util.Locale.ROOT);
			if (!com.socialcoisa.rede.preferences.domain.ContentTaxonomy.TOPIC_SET.contains(declared)) {
				throw throwInvalid("INVALID_TOPIC", "Assunto inválido: escolha um da lista.");
			}
			topic = declared;
		} else {
			topic = null;
		}
		ScheduledPost post = new ScheduledPost(newId(), userId, type, command.content(), command.pageHtml(),
			command.articleBody(), command.tags() == null ? List.of() : command.tags(),
			command.mediaIds() == null ? List.of() : command.mediaIds(),
			command.mentionedUserIds() == null ? List.of() : command.mentionedUserIds(), command.communityId(),
			topic, scheduledAt, now, null, 0);
		ScheduledPost saved = repository.save(post);
		// Scheduling is the person's action today; the later automatic publication is not presence.
		coins.recordPresence(userId);
		return saved;
	}

	@Transactional(readOnly = true)
	public List<ScheduledPost> list(String userId) {
		return repository.findByUser(userId);
	}

	@Transactional
	public void cancel(String userId, String id) {
		ScheduledPost post = repository.findById(id)
			.orElseThrow(() -> new ApiException(404, "SCHEDULED_POST_NOT_FOUND", "Post agendado não encontrado."));
		if (!post.userId().equals(userId)) {
			throw new ApiException(403, "NOT_OWNER", "Este post agendado não é seu.");
		}
		repository.delete(id);
	}

	/**
	 * Publica o que venceu. Cada item tem o seu próprio try: uma recusa não pode travar a fila
	 * dos demais. O retorno é quantos entraram no ar nesta rodada.
	 *
	 * SEM @Transactional de propósito: a publicação é um caso de uso com transação PRÓPRIA; se o
	 * lote inteiro compartilhasse uma transação, a primeira recusa a marcaria rollback-only e
	 * derrubaria até as publicações que deram certo (UnexpectedRollbackException).
	 */
	public int publishDue() {
		List<ScheduledPost> due = repository.findDue(clock.instant(), MAX_ATTEMPTS, 20);
		int published = 0;
		for (ScheduledPost post : due) {
			try {
				// As menções valem no momento da PUBLICAÇÃO: quem foi marcado é avisado quando o
				// post entra no ar, não quando ele foi agendado.
				publishPost.publish(new PublishPostCommand(new UserId(post.userId()), post.type(), post.content(),
					post.pageHtml(), post.articleBody(), post.tags(), post.mediaIds(), post.communityId(),
					post.mentionedUserIds(), post.topic()));
				repository.delete(post.id());
				published++;
			}
			catch (RuntimeException error) {
				String motivo = error instanceof ApiException api && StringUtils.hasText(api.getMessage())
					? api.getMessage()
					: "Não foi possível publicar no horário marcado.";
				String limitado = motivo.length() > 500 ? motivo.substring(0, 500) : motivo;
				repository.save(post.failed(limitado));
				LOG.warn("Post agendado {} recusado na publicação (tentativa {}): {}", post.id(), post.attempts() + 1,
					limitado);
			}
		}
		return published;
	}

	private String newId() {
		return "sched_" + UUID.randomUUID().toString().replace("-", "");
	}

	private ApiException throwInvalid(String code, String message) {
		return new ApiException(400, code, message);
	}
}

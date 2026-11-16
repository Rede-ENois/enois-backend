package com.socialcoisa.rede.feed.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.socialcoisa.rede.feed.application.port.out.DraftPostRepository;
import com.socialcoisa.rede.feed.domain.DraftPost;
import com.socialcoisa.rede.feed.domain.PostType;
import com.socialcoisa.rede.shared.web.ApiException;

/**
 * O rascunho do compositor: um por usuário, atualizado a cada salvamento — o mesmo rascunho da
 * pessoa em qualquer dispositivo. A régua aqui é a MÍNIMA para valer a pena restaurar (tipo
 * válido e algo escrito): as regras completas — tamanho, antiflood, conta restrita, enquete
 * inteira — continuam valendo na PUBLICAÇÃO, pelo mesmo caso de uso de sempre. Mídia anexada
 * não entra no rascunho (os arquivos só sobem ao publicar).
 */
@Service
public class DraftPostService {

	private final DraftPostRepository repository;
	private final Clock clock;

	public DraftPostService(DraftPostRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public record DraftCommand(PostType type, String content, String pageHtml, String articleBody,
			String topic, List<String> pollOptions, boolean pollMultipleChoice, Instant pollEndsAt,
			List<String> mentionedUserIds) {
	}

	@Transactional
	public DraftPost save(String userId, DraftCommand command) {
		if (command.type() == null) {
			throw throwInvalid("TIPO_OBRIGATORIO", "Escolha o tipo da publicação.");
		}
		if (!temConteudo(command)) {
			throw throwInvalid("CONTEUDO_OBRIGATORIO", "O rascunho está vazio.");
		}
		DraftPost existente = repository.findByUser(userId).orElse(null);
		Instant agora = clock.instant();
		DraftPost rascunho = new DraftPost(existente == null ? newId() : existente.id(), userId,
			command.type(), command.content(), command.pageHtml(), command.articleBody(), command.topic(),
			command.pollOptions() == null ? List.of() : command.pollOptions(), command.pollMultipleChoice(),
			command.pollEndsAt(), command.mentionedUserIds() == null ? List.of() : command.mentionedUserIds(),
			existente == null ? agora : existente.createdAt(), agora);
		return repository.save(rascunho);
	}

	@Transactional(readOnly = true)
	public Optional<DraftPost> find(String userId) {
		return repository.findByUser(userId);
	}

	@Transactional
	public void delete(String userId) {
		repository.findByUser(userId).ifPresent(rascunho -> repository.delete(rascunho.id()));
	}

	/**
	 * Mesma régua mínima do agendamento: cada tipo precisa do seu conteúdo. A enquete aceita
	 * alternativas pela metade (é um rascunho), mas não totalmente vazia.
	 */
	private boolean temConteudo(DraftCommand command) {
		return switch (command.type()) {
			case PAGE -> StringUtils.hasText(command.pageHtml());
			case ARTICLE -> StringUtils.hasText(command.content()) && StringUtils.hasText(command.articleBody());
			case POLL -> StringUtils.hasText(command.content())
					|| (command.pollOptions() != null
						&& command.pollOptions().stream().anyMatch(StringUtils::hasText));
			default -> StringUtils.hasText(command.content());
		};
	}

	private String newId() {
		return "draft_" + UUID.randomUUID().toString().replace("-", "");
	}

	private ApiException throwInvalid(String code, String message) {
		return new ApiException(400, code, message);
	}
}

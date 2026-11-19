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
 * Os rascunhos do compositor: uma LISTA por usuário (issue do blog — quem escreve mais de um
 * texto não pode perder material), atualizados a cada salvamento e disponíveis em qualquer
 * dispositivo. O par de métodos sem id ({@link #save} com substituição e {@link #find} com o
 * mais recente) continua existindo para o compositor atual, que ainda pensa em "o rascunho";
 * quem chega com id ({@link #saveAs}, {@link #list}, {@link #deleteById}) manipula a lista.
 * A régua aqui é a MÍNIMA para valer a pena restaurar (tipo válido e algo escrito): as regras
 * completas — tamanho, antiflood, conta restrita, enquete inteira — continuam valendo na
 * PUBLICAÇÃO, pelo mesmo caso de uso de sempre. Mídia anexada não entra no rascunho (os
 * arquivos só sobem ao publicar).
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
		DraftPost existente = repository.findByUser(userId).orElse(null);
		return saveInto(userId, existente == null ? null : existente.id(), command);
	}

	/**
	 * Salva em um rascunho específico da lista; id nulo cria um novo. Um id que não é da pessoa
	 * recusa com 404 (não 403: a existência de um id alheio não é informação que ela precisa ter).
	 */
	@Transactional
	public DraftPost saveAs(String userId, String draftId, DraftCommand command) {
		if (draftId != null) {
			DraftPost existente = repository.findById(draftId)
				.filter(draft -> draft.userId().equals(userId))
				.orElseThrow(() -> new ApiException(404, "DRAFT_NOT_FOUND", "Rascunho não encontrado."));
			return saveInto(userId, existente.id(), command);
		}
		return saveInto(userId, null, command);
	}

	private DraftPost saveInto(String userId, String existingId, DraftCommand command) {
		if (command.type() == null) {
			throw throwInvalid("TIPO_OBRIGATORIO", "Escolha o tipo da publicação.");
		}
		if (!temConteudo(command)) {
			throw throwInvalid("CONTEUDO_OBRIGATORIO", "O rascunho está vazio.");
		}
		DraftPost existente = existingId == null ? null : repository.findById(existingId).orElse(null);
		Instant agora = clock.instant();
		DraftPost rascunho = new DraftPost(existente == null ? newId() : existente.id(), userId,
			command.type(), command.content(), command.pageHtml(), command.articleBody(), command.topic(),
			command.pollOptions() == null ? List.of() : command.pollOptions(), command.pollMultipleChoice(),
			command.pollEndsAt(), command.mentionedUserIds() == null ? List.of() : command.mentionedUserIds(),
			existente == null ? agora : existente.createdAt(), agora);
		return repository.save(rascunho);
	}

	/** A lista inteira da pessoa, do mais recente ao mais antigo. */
	@Transactional(readOnly = true)
	public List<DraftPost> list(String userId) {
		return repository.findAllByUser(userId);
	}

	@Transactional(readOnly = true)
	public Optional<DraftPost> find(String userId) {
		return repository.findByUser(userId);
	}

	@Transactional
	public void delete(String userId) {
		repository.findByUser(userId).ifPresent(rascunho -> repository.delete(rascunho.id()));
	}

	/** Descarta um rascunho da lista. Posse confere igual ao saveAs: 404 quando não é dele. */
	@Transactional
	public void deleteById(String userId, String draftId) {
		repository.findById(draftId)
			.filter(draft -> draft.userId().equals(userId))
			.ifPresentOrElse(draft -> repository.delete(draft.id()),
				() -> { throw new ApiException(404, "DRAFT_NOT_FOUND", "Rascunho não encontrado."); });
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

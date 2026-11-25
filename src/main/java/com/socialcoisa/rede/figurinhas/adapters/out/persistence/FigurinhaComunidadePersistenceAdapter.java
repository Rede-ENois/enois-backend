package com.socialcoisa.rede.figurinhas.adapters.out.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.socialcoisa.rede.figurinhas.application.port.out.FigurinhasComunidadeRepository;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidade;
import com.socialcoisa.rede.figurinhas.domain.FigurinhaComunidadeEvento;

@Component
class FigurinhaComunidadePersistenceAdapter implements FigurinhasComunidadeRepository {

	private final SpringDataFigurinhaComunidadeJpaRepository figurinhas;
	private final SpringDataFigurinhaEventoJpaRepository eventos;

	FigurinhaComunidadePersistenceAdapter(SpringDataFigurinhaComunidadeJpaRepository figurinhas,
			SpringDataFigurinhaEventoJpaRepository eventos) {
		this.figurinhas = figurinhas;
		this.eventos = eventos;
	}

	@Override
	public FigurinhaComunidade save(FigurinhaComunidade figurinha) {
		Optional<FigurinhaComunidadeJpaEntity> existente = figurinhas.findById(figurinha.id());
		FigurinhaComunidadeJpaEntity entity;
		if (existente.isPresent()) {
			entity = existente.get();
			entity.aplicarEstado(figurinha);
		}
		else {
			entity = FigurinhaComunidadeJpaEntity.from(figurinha);
		}
		return figurinhas.save(entity).toDomain();
	}

	@Override
	public Optional<FigurinhaComunidade> findById(String id) {
		return figurinhas.findById(id).map(FigurinhaComunidadeJpaEntity::toDomain);
	}

	@Override
	public Optional<FigurinhaComunidade> findAtivaByCodigo(String codigo) {
		return figurinhas.findByCodigoAndAtivo(codigo, FigurinhaComunidadeJpaEntity.ATIVA)
			.map(FigurinhaComunidadeJpaEntity::toDomain);
	}

	@Override
	public List<String> ativosEntre(Collection<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return figurinhas.findAtivosEntre(ids);
	}

	@Override
	public List<FigurinhaComunidade> page(String query, int offset, int limit) {
		// Lote já deslocado, a partir da primeira página: o SQL recebe apenas o LIMIT.
		PageRequest page = PageRequest.of(0, offset + Math.max(1, limit));
		String termo = normalizarTermo(query);
		List<FigurinhaComunidadeJpaEntity> linhas = termo.isEmpty()
			? figurinhas.findAtivas(page)
			: figurinhas.buscar(termo, page);
		return linhas.stream().skip(Math.max(0, offset)).limit(Math.max(1, limit))
			.map(FigurinhaComunidadeJpaEntity::toDomain).toList();
	}

	@Override
	public long count(String query) {
		String termo = normalizarTermo(query);
		return termo.isEmpty() ? figurinhas.contarAtivas() : figurinhas.contarBusca(termo);
	}

	@Override
	public long countAtivasDoAutor(String autorId) {
		return figurinhas.contarAtivasDoAutor(autorId);
	}

	@Override
	public List<FigurinhaComunidadeEvento> eventos(String figurinhaId) {
		return eventos.findByFigurinhaIdOrderByCriadoEmAsc(figurinhaId).stream()
			.map(FigurinhaEventoJpaEntity::toDomain).toList();
	}

	@Override
	public void saveEvento(FigurinhaComunidadeEvento evento) {
		eventos.save(FigurinhaEventoJpaEntity.from(evento));
	}

	/**
	 * Padrão do LIKE com curingas escapados: o `%` de quem busca é caractere, não "tudo". O termo
	 * vai em caixa baixa — as colunas também são comparadas em caixa baixa, nos dois bancos.
	 */
	static String normalizarTermo(String query) {
		if (query == null || query.isBlank()) {
			// Termo vazio lista o catálogo inteiro; não é uma busca por "%", que casa com tudo por acidente.
			return "";
		}
		String termo = query.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
			.replace("_", "\\_");
		return "%" + termo + "%";
	}
}

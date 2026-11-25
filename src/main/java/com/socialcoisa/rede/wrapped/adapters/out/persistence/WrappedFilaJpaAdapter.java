package com.socialcoisa.rede.wrapped.adapters.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.socialcoisa.rede.wrapped.application.port.out.WrappedFilaRepository;
import com.socialcoisa.rede.wrapped.domain.WrappedEstado;

@Repository
class WrappedFilaJpaAdapter implements WrappedFilaRepository {

	private final SpringDataWrappedJpaRepository jpa;

	WrappedFilaJpaAdapter(SpringDataWrappedJpaRepository jpa) {
		this.jpa = jpa;
	}

	@Override
	public boolean existe(int ano, String userId) {
		return jpa.findByUserIdAndAno(userId, ano).isPresent();
	}

	@Override
	@Transactional
	public void inserirPendente(String userId, int ano, long posicao, int prioridade, int versaoAlgoritmo,
			Instant agora) {
		jpa.inserirPendente(userId, ano, posicao, prioridade, versaoAlgoritmo, agora);
	}

	@Override
	public boolean concluida(String userId, int ano) {
		return jpa.existsByUserIdAndAnoAndEstado(userId, ano, WrappedEstado.DONE.name());
	}

	@Override
	public List<String> proximas(int ano, int limite) {
		return jpa.primeirasPendentes(ano, PageRequest.of(0, limite));
	}

	@Override
	@Transactional
	public boolean reservar(String userId, int ano, Instant agora, Instant expiraEm) {
		return jpa.claim(userId, ano, agora, expiraEm) == 1;
	}

	@Override
	@Transactional
	public int expirarReservas(int ano, Instant agora) {
		return jpa.expirarReservas(ano, agora);
	}

	@Override
	@Transactional
	public void concluir(String userId, int ano, String resultadoJson, boolean dadosInsuficientes, Instant agora) {
		jpa.concluir(userId, ano, resultadoJson, dadosInsuficientes, agora);
	}

	@Override
	@Transactional
	public void falhar(String userId, int ano, String falha, Instant agora) {
		jpa.falhar(userId, ano, falha == null ? "" : falha, agora);
	}

	@Override
	public Optional<Registro> buscar(String userId, int ano) {
		return jpa.findByUserIdAndAno(userId, ano).map(e -> new Registro(e.userId(), e.ano(),
				WrappedEstado.parse(e.estado()), e.posicao(), e.prioridade(), e.algoritmoVersao(), e.resultado(),
				e.falha(), e.iniciadoEm(), e.concluidoEm()));
	}

	@Override
	public long contar(int ano, WrappedEstado estado) {
		return jpa.contar(ano, estado.name());
	}
}

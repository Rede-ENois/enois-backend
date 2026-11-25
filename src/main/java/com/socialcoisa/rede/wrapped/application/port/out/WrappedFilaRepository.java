package com.socialcoisa.rede.wrapped.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.socialcoisa.rede.wrapped.domain.WrappedEstado;

/**
 * As operações da fila de retrospectivas. A fila é a própria tabela de resultados filtrada por
 * estado — posição já reflete a credibilidade no momento da construção.
 */
public interface WrappedFilaRepository {

	/** Já existe a linha da conta no ano (em qualquer estado)? — para não reconstruir a fila. */
	boolean existe(int ano, String userId);

	/** Cria a linha PENDING na posição dada (inserção condicional: não sobrescreve existente). */
	void inserirPendente(String userId, int ano, long posicao, int prioridade, int versaoAlgoritmo,
			Instant agora);

	/** Conta com resultado concluído (DONE)? */
	boolean concluida(String userId, int ano);

	/** As próximas contas da fila, na ordem de prioridade. */
	List<String> proximas(int ano, int limite);

	/** Reserva a conta para esta instância; falso quando outra instância venceu a corrida. */
	boolean reservar(String userId, int ano, Instant agora, Instant expiraEm);

	/** Devolve à fila as reservas vencidas (instância que caiu no meio da conta). */
	int expirarReservas(int ano, Instant agora);

	/** Grava o resultado já calculado e conclui a conta (só se ela estiver reservada por nós). */
	void concluir(String userId, int ano, String resultadoJson, boolean dadosInsuficientes, Instant agora);

	/** Registra a falha e libera a conta da fila (não volta a ser tentada sem decisão). */
	void falhar(String userId, int ano, String falha, Instant agora);

	/** A linha crua, para a leitura da retrospectiva e a auditoria. */
	Optional<Registro> buscar(String userId, int ano);

	long contar(int ano, WrappedEstado estado);

	/** Uma linha como está no banco. */
	record Registro(String userId, int ano, WrappedEstado estado, long posicao, int prioridade,
			int algoritmoVersao, String resultadoJson, String falha, Instant iniciadoEm, Instant concluidoEm) {
	}
}

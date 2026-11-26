package com.socialcoisa.rede.wrapped;

/**
 * O Énóis Wrapped: a retrospectiva anual de cada conta.
 *
 * O módulo monta, a partir do histórico REAL do ano (publicações, comentários, reações, reposts,
 * compartilhamentos, comunidades), um reconhecimento de padrões determinístico: assuntos que
 * marcaram o ano (reaproveitando o extrator do Papo Reto), publicações marcantes, discussões,
 * interações, ritmo, perfil de participação e impacto. Nada é inventado: seção sem dado fica vazia.
 *
 * A geração é assíncrona e fora do horário de pico: uma fila persistente priorizada pela
 * credibilidade (coluna materializada em {@code users.reputation}) é processada nas madrugadas de
 * 26/12 a 01/01, entre 00:00 e 05:00, uma conta por vez, com reserva atômica e resumo persistido.
 * A leitura é sempre da própria sessão: a rota não aceita a conta como parâmetro.
 */
public interface WrappedModule {

}

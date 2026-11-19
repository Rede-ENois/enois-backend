package com.socialcoisa.rede.paporeto.domain;

import java.time.Instant;

/**
 * Um trecho do ranking agregado: a linha da timeline do assunto num escopo — quantas falas o
 * subject teve no bucket de hora. A timeline é reconciliada a cada corte (o mesmo bucket
 * volta ao mesmo número), então é série histórica de verdade: conteúdo removido sai dela na
 * próxima passagem, sem duplicar e sem fila de soma.
 */
public record PapoIntervalo(String escopo, String subject, Instant hora, long falas) {

}

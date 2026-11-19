package com.socialcoisa.rede.paporeto;

/**
 * O Papo Reto: os assuntos em alta do Explorar, medidos pelo que a comunidade conversa — nunca
 * declarados por ela.
 *
 * O módulo lê o que já é público (posts e comentários do feed, desabafos, confissões do
 * Confessionário), extrai as palavras e expressões que nomeiam a conversa (n-gramas com
 * stopwords em português), agrupa variações do mesmo assunto (clustering por palavra nuclear)
 * e pontua: o peso das fontes, quantas pessoas distintas falaram e o crescimento recente, com
 * decaimento pelo tempo. O resultado é uma lista de subjects com status EMERGENTE, PERSISTENTE
 * ou HOT, o total de falas analisadas e a timeline do assunto — global e por comunidade.
 *
 * Nada aqui é opinião de sistema nem enquete: mesmo texto, mesmo número, sempre — algoritmo
 * local determinístico, sem LLM e sem serviço externo. O texto completo não é gravado: da
 * fonte sai apenas a expressão normalizada, com autor interno (só para contar pessoas
 * distintas), comunidade de origem e a âncora de deduplicação. Uma expressão que só uma mão
 * de gente falou não vira "alta": o chão de pessoas distintas existe para que um assunto
 * bombardeado por uma conta só não apareça como conversa da comunidade. Conteúdo removido
 * pela moderação sai da conta na passagem seguinte do corte (a agregação é reescrita a partir
 * das falas, e a fala nasce só do conteúdo ativo).
 */
public interface PapoRetoModule {

}

# ADR-010 — `NODE_LOCAL`: prioridade de ordenação, não peso variável

**Status:** aceita (Fase 4)
**Data:** 2026-11-12

## Contexto

Três documentos prometem à infraestrutura própria do Énóis um lugar diferente na fila:

- [02 — Modelo de ameaças](../rede-distribuida/02-modelo-de-ameacas.md): "`NODE_LOCAL` está em T2 de
  propósito: ser da casa não dá confiança automática. O que ele ganha é **prioridade de
  escalonamento**, não permissão extra";
- o javadoc de `NodeProfile.NODE_LOCAL`, no mesmo sentido;
- [06 — Contingência](../rede-distribuida/06-contingencia.md) §2, com um diagrama que falava em
  "aumenta peso dos nós locais" e "reduz peso gradualmente".

O código, até a Fase 4, não implementava nenhuma das duas leituras: `choose()` ordenava por
degradação → folga → jitter e ignorava o perfil. A permissão do local **já existia** — `allowedFor`
aceita qualquer tipo para `NODE_LOCAL`, e `NetworkNode.accepts` libera classe de dado que não sai da
infra autorizada por causa dele. O que faltava era a posição na ordem.

## Decisão

O critério de ordenação passa a ser **degradação → origem → folga → jitter**. Origem significa:
`NODE_LOCAL` antes dos demais.

Quatro propriedades, e cada uma é uma alternativa rejeitada:

1. **É ordenação, não peso.** Peso variável exigiria suavização, um segundo conjunto de limiares
   (quanto sobe, quanto desce, com que histerese) e um terceiro lugar onde o comportamento muda sem
   ser visível. O efeito observável que se quer — "o pico é absorvido na casa antes de chegar ao
   participante" — já é obtido com ordem constante: o local recebe até encher, e a partir daí a fila
   vai para os participantes. Peso só mudaria *quando* isso acontece, e mudaria por um número que
   ninguém sabe calibrar antes de ter carga real.

2. **Vem depois da degradação.** Prioridade não é perdão: nó local lento ou errando perde para
   qualquer externo saudável, como manda a §2. Um local degradado que continuasse na frente
   concentraria falhas justamente no nó cuja queda é a da casa.

3. **Vem antes da folga.** Aplicada depois, a origem só decidiria entre nós com folga idêntica — e no
   caso normal (todos com vaga e folga parecida) a ordem seria o jitter. A prioridade existiria na
   letra e sumiria no empate, que é onde ela é necessária.

4. **Não altera filtro nem permissão.** O local segue sujeito a vivo, fora da janela de remoção, com
   vaga e não saturado. Nenhum tipo de tarefa ou classe de dado é liberado por ele — isso já era
   regra do perfil e não muda aqui.

## Consequências

- O local absorve pico primeiro **até o limite de concorrência que ele mesmo declarou**; a partir
  daí o excedente vai para os participantes, sem espera (o filtro de vaga é anterior à ordenação).
- Um `NODE_LOCAL` com CPU/memória pequenas e `NODE_WORKER` grandes passa a receber mais trabalho do
  que a capacidade física sugere. É intencional: o limite de concorrência é o número declarado pelo
  operador, e quem opera o nó da casa pode declará-lo abaixo do número de núcleos se for o caso. O
  freio por saturação (`SATURATED_CPU_PERCENT`) continua valendo sobre o heartbeat.
- Empates entre dois nós locais seguem pelo jitter, e entre dois externos também: a origem só separa
  local de externo.
- A drenagem ("reduz gradualmente") continua não implementada — é o item "recuperação gradual" da
  fase 4 e não depende deste critério.
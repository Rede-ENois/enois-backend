# Duplicação contextual no feed

## Contrato

A deduplicação de publicação e comentário compara a conta, o texto normalizado e o contexto
validado pelo servidor. Texto igual, sozinho, não torna duas contribuições duplicadas.

- **Publicação:** considera tipo, identidade social/profissional, comunidade, canal, mídia,
  HTML sanitizado de página, corpo de artigo e alternativas de enquete efetivamente aceitos.
- **Comentário:** considera post de destino, comentário respondido e mídia aceita. Respostas iguais
  em conversas diferentes, mesmo dentro de um post, têm contextos distintos.
- Tags, menções, spoiler e campos ignorados pelo tipo de publicação não criam outro contexto.
- O texto principal mantém a comparação sem diferença de caixa e com espaços normalizados,
  usando `Locale.ROOT`. Artigo, HTML e alternativas preservam os valores validados e sua ordem.
  Anexos removidos não distinguem conteúdo; IDs de mídia visível são ordenados apenas para a
  comparação, pois as coleções persistidas não representam posição dos anexos.
- Comentário só de mídia e página sem título também entram na comparação do conteúdo efetivo.

Repetir o mesmo conteúdo no mesmo contexto continua retornando **409 `DUPLICATE_CONTENT`**.
A janela permanece em `rede.rate.duplicate-window-minutes`, com padrão de 60 minutos e zero para
desativar. Não há alteração de payload, autenticação, autorização, limites de frequência ou sanções.

## Implementação e limites

`ContextoDuplicacaoFeed` monta os campos a partir dos valores que serão persistidos, depois de
validar destino, acesso e conteúdo. `ContentDuplicationGuard` usa uma reserva atômica por hash,
com tamanho de cada campo prefixado para não confundir campos contendo delimitadores.
Nenhum texto ou contexto bruto é armazenado nesse cache.

Uma reserva é liberada quando a transação sofre rollback; erro de validação não registra uma
publicação. Requisições concorrentes com a mesma chave não passam juntas. A memória continua
limitada a 50 mil entradas e a janela não é renovada por tentativas rejeitadas. O estado é local
ao processo, como antes: não substitui idempotência persistida nem coordenação entre instâncias.

O motor de risco recebe exatamente o mesmo contexto para o sinal `DUPLICATE_CONTENT`, com versão
de regra `v2`. Outros sinais e seus pesos continuam inalterados. O automod de spam/flood continua
sendo um fluxo separado após o commit; permitir contextos diferentes não confirma nem descarta spam.
Esta correção não adiciona análise semântica ou chamada à IA na publicação.

O agendador usa o mesmo caso de uso e herda a comparação contextual. O blog, por sua vez,
entrega apenas o título ao criar o ponteiro no feed: o corpo do blog não participa desta
comparação. Dois textos de blog com o mesmo título ainda podem colidir na janela. Corrigir
esse contrato interno exige uma alteração própria; não se presume que o título represente o corpo.
Mensagens privadas não usam esta guarda e não tiveram sua regra alterada.

## Verificação

```bash
./mvnw -Dtest=CommentContractTests,PostDuplicationContextTests,MediaDuplicationContextTests,ContentDuplicationGuardTests,RiskEngineTests test
./mvnw -DskipTests compile
./mvnw test
```

Os testes de integração usam MockMvc, os serviços reais e H2 com deduplicação ligada. Cobrem
conversas e destinos distintos, publicações com mesmo título e conteúdo efetivo diferente,
mídia, metadados irrelevantes, validação, rollback e manutenção do bloqueio de duplicatas reais.
Os unitários cobrem normalização, isolamento entre contas/contextos, consulta sem efeito,
reserva concorrente e configuração desligada.

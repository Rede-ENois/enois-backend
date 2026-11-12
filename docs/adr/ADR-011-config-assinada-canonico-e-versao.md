# ADR-011 — Config assinada: canônico, não JSON; versão da permissão, não relógio

**Status:** aceita (Fase 5)
**Data:** 2026-11-12

## Contexto

O contrato `03-contratos.md` documentava `GET /network/nodes/me/config` desde o desenho da fase 1 —
"o nó confere a assinatura antes de aplicar" — e o código não tinha a rota. `grep -rn "nodes/me"
src/main src/test` não devolvia nada, e não havia qualquer menção a certificado, CSR ou mTLS no
backend.

Três decisões tiveram de ser tomadas para implementá-la, e cada uma tinha uma alternativa óbvia que
foi rejeitada:

1. **O que é assinado** — o JSON serializado (o caminho curto: assina o corpo, roda `ObjectMapper`)
   ou um documento canônico montado campo por campo.

2. **De onde vem `issuedAt`/`configVersion`** — do relógio da requisição (o caminho curto: `Instant.now()`),
   ou de um estado persistido no nó.

3. **O que acontece sem chave de assinatura configurada** — recusar a rota (fail-closed), servir sem
   assinatura e dizer isso, ou servir com `signed:true` e assinatura vazia.

## Decisão

**Assina-se uma linha canônica, não o JSON.** A linha é
`formatVersion|configVersion|nodeId|profiles|cpuCores|memoryMb|diskMb|bandwidthMbps|allowedTasks|issuedAt`,
com listas ordenadas e em JSON compacto. Assinar o corpo serializado amarraria a verificação ao
formato de serialização de uma biblioteca: subir a versão do Jackson, mudar uma flag de indentação
ou habilitar escrita de datas como array quebraria a assinatura de todo nó já instalado, sem que
nenhuma permissão tivesse mudado. O canônico é contrato; o JSON é transporte.

**`configVersion` e `issuedAt` vêm do ciclo de vida do nó (V284), não do relógio.** A versão nasce
`1` no enroll e soma `1` em cada transição administrativa, dentro do próprio `UPDATE`
(`n.configVersion = n.configVersion + 1`); o `issuedAt` é carimbado com o instante da transição.
Duas leituras seguidas devolvem o mesmo documento — e, como Ed25519 é determinística, a mesma
assinatura.

Sem isso, `Instant.now()` por leitura faria o runtime — que trata config nova como evento e
revalida a cada documento diferente — reaplicar permissão a cada segundo. O ruído esconderia
exatamente a mudança que importa.

**Sem chave configurada, `signed:false` e `signature:null`.** Não é fail-open: quem decide aceitar
config sem assinatura é o runtime, e o padrão dele é recusar. A obrigação do Control Plane aqui é só
não mentir — `signed:true` com campo vazio é uma afirmação que o nó não tem como conferir.
Chave presente e inválida vira `WARN` no log e o mesmo `signed:false`; falha ao assinar com chave
válida é `ERROR` e `signed:false`, nunca exceção que derruba a rota.

## Consequências

- **Duas versões no payload, de propósito.** `formatVersion` describe o formato do documento (sobe
  quando um runtime antigo não entenderia); `configVersion` descreve a permissão daquele nó. Confundir
  as duas faria o nó recusar config nova por "formato" quando a permissão só mudou de estado.
- **Transição administrativa é o único produtor de versão nova.** Não há contador em memória nem job
  que precise lembrar de incrementar: a versão é derivada do `UPDATE` que já existe. A migração V284
  é aditiva e idempotente, e linha anterior a ela cai no `created_at` para não inventar data.
- **`publicKey` vai em toda resposta.** É o pino que o instalador grava no enroll; o runtime confere
  toda config seguinte contra ele sem depender do transporte desta resposta. A chave pública não é
  segredo, então publicá-la não abre nada.
- **Nó pendente de aprovação lê a própria config** (200, `operable:false`, `allowedTasks` vazio). É o
  mesmo critério do scheduler (`accepts` exige `canOperate`) dito ao runtime: 403 aqui deixaria o
  instalador sem resposta justamente no passo em que ele espera aprovação humana.
- **Ainda não há mTLS nem certificado individual.** A autoridade do canal continua sendo a API key
  por nó; a config assinada protege o que o nó **aplica**, não o canal. Registrado como pendência em
  `04-identidade-e-autorizacao.md`.

## Provas

- `NetworkConfigSignerTests` (5): assina/confere o canônico; campo trocado quebra; ordem diferente
  do mesmo conteúdo produz a mesma linha; assinatura corrompida é recusa limpa; chave malformada vira
  "sem chave".
- `NetworkConfigServiceTests` (6): assinatura confere com o pino publicado; sem chave → `signed:false`;
  chave inválida não mente; duas leituras → mesmo documento e mesma assinatura; nó que não opera
  recebe lista vazia; `NODE_LOCAL` recebe todos os tipos na ordem do enum.
- `NetworkNodesTests` (13): rota com credencial de nó, payload real, duas leituras idênticas,
  transição de status compondo versão, 401 sem credencial, nó pendente lendo a própria config.
- Migração V284 aplicada duas vezes em MariaDB 11.4.13 real, com linha anterior preservada
  (`config_version = 1`, `config_issued_at` nulo) e transição simulada (`+1` e carimbo).
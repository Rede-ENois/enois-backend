# 21 — Nó de reserva: cópia do banco e promoção com backup (brief cooperativo §11/§12)

A promoção de autoridade (`06-contingencia.md` §4, V317) trocava **quem manda** e deixava a réplica
de destino **sem os dados** de quem mandava. Promover sem cópia é trocar o nome do controlador e
recomeçar do zero: o failover só existe se o nó de reserva tiver o estado para assumir. Este
documento descreve o que passou a existir (V364) e, na última seção, o que **não** existe.

## 1. O desenho

Quem lê o banco é o Control Plane. O nó de reserva **recebe os bytes** do dump, confere o digest e
guarda. A credencial de leitura do banco não sai do Control Plane — um nó de participante com usuário
de replicação seria um segundo caminho de leitura do dado pessoal de todo mundo.

```
admin ──POST /admin/network/authority/standby/copy {nodeId}──▶ Control Plane  (linha SYNCING, V364)
nó    ──GET  /network/standby/dump?offset=N────────────────▶ dump + X-Standby-Sha256 (do dump INTEIRO)
nó    ──POST /network/standby/complete {bytes, sha256}─────▶ CONFERE o digest → READY
admin ──POST /admin/network/authority/promote-standby──────▶ exige READY → sobe o termo
```

Três decisões que o desenho fixa:

- **Cópia parcial nunca é `READY`.** Só o fim do curso, com o digest conferido contra o que o servidor
  enviou, grava `READY`. Metade do banco é pior que banco nenhum: parece que funcionou.
- **O início de uma cópia nova não apaga a prova da anterior.** O upsert (`startSync`) toca só as
  colunas da tentativa; `completed_at`, `bytes` e `sha256` da cópia completa continuam na linha. Quem
  cai no meio não "nunca teve cópia".
- **Retomada não é falha.** A conexão que cai aos 90% pede `offset` e o servidor manda a cauda; a
  linha registra `byte_start`. O digest é sempre o do dump **inteiro** — conferir só a cauda aprovaria
  um arquivo cujo começo está corrompido.

## 2. A cópia do dia e a da madrugada

| quando | o que roda | o quê |
|---|---|---|
| a cada 6 h | `StandbySyncWorker.refreshDuringTheDay` | só age se **nenhum** nó tem cópia completa dentro da janela (`rede.network.standby.refresh-hours`) |
| 03:00 (servidor) | `StandbySyncWorker.fullSyncBeforeDawn` | a sincronização completa; hoje é a mesma operação da cópia (ver §4) |
| na promoção | `promote-standby` | sem cópia `READY`, dispara a cópia e recusa com `409 NETWORK_STANDBY_COPY_MISSING` |

O refresh **não inventa reserva**: só copia para nós que já têm linha. A lista de reservas é do
operador — promover um nó a reserva transforma o banco do Control Plane em dado que ele guarda, e
isso não pode acontecer por um agendador ter escolhido.

O job é **desligado por padrão** (`rede.network.standby.auto-sync.enabled=false`). Um job que
consulta o banco em desenvolvimento sem nó de reserva nenhum só gastaria I/O. Ligado, o desenho fica
completo.

## 3. O dump

### 3.1 Quem serializa — JDBC, não binário externo (#953)

`JdbcStandbyDumpSource` (`rede.network.standby.dump.enabled=true`) é a implementação padrão desde a
#953. Ela não exige `mariadb-dump` no `PATH` nem senha nova: usa a conexão que o aplicativo já tem.

**Antes (o que quebrava em PROD):** a cópia dependia do binário na imagem e de uma propriedade que o
ambiente de produção não tinha. O container (`eclipse-temurin:25-jre-noble`) não traz o cliente da
MariaDB, então ligar a propriedade não bastava: `GET /network/standby/dump` respondia 503 e a cadeia
de reserva inteira não funcionava.

**Agora:** a sessão de leitura roda em `REPEATABLE READ` com transação aberta antes da primeira tabela
e fechada depois da última — todas as tabelas saem do **mesmo ponto no tempo**, como o
`--single-transaction` do `mariadb-dump`. A transação é só de leitura. O arquivo tem o formato do
`mariadb-dump`: cabeçalho de sessão, `DROP TABLE IF EXISTS`, `CREATE TABLE` (pelo `SHOW CREATE TABLE`,
que leva índice, colação e `AUTO_INCREMENT`), `INSERT` em lotes de `dump.batch-rows` (500) e o rodapé.
Binário vai em hexadecimal; sem gatilhos, rotinas, eventos, usuários ou grants — a réplica é um banco,
não um servidor, e credencial não viaja para o nó.

`CommandLineStandbyDumpSource` continua existindo e só entra com
`rede.network.standby.dump.source=cli` explícito (para quem prefere o binário e o tem). As duas fontes
ligadas pela mesma propriedade seriam ambiguidade de bean, e a que não roda em produção não pode ser a
escolhida por omissão.

### 3.2 O dump é gerado uma vez por janela

**Antes:** cada `GET /network/standby/dump` rodava o dump inteiro do zero. Um nó que perdia a conexão
aos 90% e retomava fazia o banco de produção ser lido do começo, e a retomada concatenava metades de
dois dumps diferentes (digest diferente = confirmação recusada para sempre).

**Agora:** o dump fica em memória por `rede.network.standby.dump.cache-seconds` (600 s por padrão) e a
retomada devolve **o mesmo arquivo** — mesmo digest, mesmos bytes. O teto é curto de propósito: dentro
da janela, uma escrita nova no banco não aparece na cópia; o que a janela evita é o I/O de re-dumpar a
cada tentativa. `cache-seconds=0` desliga o cache. A prova é `StandbyCacheTests`, com uma fonte que
muda a cada chamada (o caso real): é ela que discrimina — a fonte da rota é estável e regenerar daria
o mesmo byte.

### 3.3 O dump é conferido antes de sair

`verify()` roda logo depois da geração: cada tabela lida tem `CREATE TABLE` e, se tinha linha no
snapshot, um `INSERT`. Tabela com linha na origem e sem o dado no arquivo é **recusada** (a cópia vira
`FAILED` com o motivo). O carregamento de verdade — o arquivo abre e devolve o dado num MariaDB real —
está em `scripts/tests/standby-dump-migration-test.sh` (PASS 15), com texto com aspas/barra/nova linha,
binário, `NULL`, número negativo, bigint grande e timestamp com milissegundos.

## 4. O que este ciclo NÃO entrega — declarado, não escondido

1. **A sincronização completa é a cópia inteira, não uma cópia + binlog.** Não existe captura de
   alterações do banco do Control Plane (o §4 do brief cooperativo, `14-coop-captura-de-alteracoes.md`,
   depende de binlog e usuário de replicação — decisão externa). Entre a cópia de 03:00 e o failover,
   as escritas do dia **não** estão no nó. Quem assume acerta o relógio e perde o que não está na
   cópia; isso é lacuna, não garantia.
2. **Nenhum nó da rede roda o dump automaticamente ainda.** O `dump` é ligado no Control Plane; o
   nó precisa de uma versão do runtime que chame `GET /network/standby/dump` e
   `POST /network/standby/complete`. O contrato existe e está testado; o runtime que o consome, não.
3. **O nó não valida o banco recebido.** Ele confere o digest (que prova que os bytes são os que o
   servidor mandou), não que o dump abre. O Control Plane confere o conteúdo antes de enviar (§3.3) e
   o harness carrega o arquivo num MariaDB real; o nó ainda não faz a sua própria carga de prova. Um
   dump que só quebrasse num detalhe do dialeto do nó passaria. Mas o nó **agora relata** a falha de
   restauração (`POST /network/standby/failed`), e a linha vira `FAILED` com o motivo — antes ela
   ficava `SYNCING` para sempre.
4. **Não há aprovação de reserva no painel do admin.** A "reserva" deste ciclo é o nó que tem cópia
   `READY`; declarar um nó como reserva oficial (e alertar quando ele não tem cópia do dia) é do
   painel, e não foi entregue.
5. **Cópia do banco no nó não é cifrada pelo Control Plane.** Vai cifrada pelo transporte (mTLS de
   borda quando configurado); em repouso no nó, a proteção é a do volume dele — diferente do corpo de
   objeto, que tem envelope (`19-…` §7.1). Um nó de terceiro com uma cópia do banco do Control Plane
   é dado pessoal no disco de quem participa, e isso merece decisão própria.
6. **Sem rotação/retenção de cópias.** Uma linha por (nó, autoridade): cada cópia nova sobrescreve o
   registro da anterior. Não existe histórico de "a cópia de três dias atrás", nem retenção por
   tamanho.

## 5. Contrato

### Admin (`X-Session-Token` de admin)

| rota | efeito |
|---|---|
| `POST /admin/network/authority/standby/copy` `{nodeId}` | inicia a cópia; 404 `STANDBY_NODE_NOT_FOUND` para nó inexistente |
| `POST /admin/network/authority/standby/full-sync` `{nodeId}` | a sincronização completa (§2) |
| `GET /admin/network/authority/standby?nodeId=` | `canTakeOver`, `readyCopies`, `failures`, `reason`, `copies[]` |
| `GET /admin/network/authority/standby/dump?nodeId=&offset=` | diagnóstico: o trecho e o digest no cabeçalho |
| `POST /admin/network/authority/promote-standby` `{instanceId, nodeId, reason}` | promoção com a cópia exigida; 409 se não houver `READY` |

### Nó (`X-Node-Id` + `X-Node-Key`)

| rota | efeito |
|---|---|
| `GET /network/standby/dump?offset=` | o trecho; `X-Standby-Sha256` (dump inteiro), `X-Standby-Total-Bytes`, `X-Standby-Byte-Start` |
| `POST /network/standby/complete` `{bytes, sha256}` | digest diferente do enviado → `FAILED`; nó não confirma a cópia de outro (`403 STANDBY_NODE_MISMATCH`) |
| `POST /network/standby/failed` `{reason}` | o nó conta que não conseguiu gravar/restaurar; a linha vira `FAILED` com o motivo, em vez de ficar `SYNCING` para sempre (#953 item 4) |

Códigos: `STANDBY_NODE_REQUIRED`, `STANDBY_NODE_NOT_FOUND`, `STANDBY_COPY_NOT_STARTED`,
`STANDBY_COPY_MISSING`, `STANDBY_DUMP_UNAVAILABLE`, `STANDBY_OFFSET_INVALID`,
`STANDBY_OFFSET_BEYOND_DUMP`, `STANDBY_NODE_MISMATCH`.

## 6. Prova

| o quê | onde |
|---|---|
| promoção sem cópia recusa e pede a cópia; termo não sobe | `NetworkStandbyTests.promoverSemCopiaRecusaEPedeACopia` |
| cópia completa pelo nó deixa a reserva apta a assumir | `copiaConcluidaPeloNoTornaOReservaAptoAAssumir` |
| digest diferente do enviado não vira `READY` | `digestDiferenteDoEnviadoNaoViraCopiaPronta` |
| início de cópia nova preserva a prova da anterior | `novaCopiaEmAndamentoPreservaAProvaDaAnterior` |
| retomada manda a cauda e o digest do dump inteiro | `retomadaEnviaACaudaComODigestDoDumpInteiro` |
| banco indisponível → `FAILED`, sem cópia boa | `bancoIndisponivelNaoViraCopiaBoa` |
| dump abaixo do piso → `FAILED` | `dumpAbaixoDoPisoFalhaENaoViraCopiaBoa` |
| nó não confirma a cópia de outro | `noNaoConfirmaCopiaDeOutroNo` |
| promoção com cópia sobe o termo e audita o nó | `comCopiaProntaPromocaoSobeOTermoEAuditaONo` |
| refresh não recopia com cópia do dia; recopia quando velha | `refreshNaoCopiaDeNovoQuandoJaHaCopiaDoDia`, `refreshCopiaDeNovoQuandoACopiaEstaVelha` |
| membro comum não lê nem pede cópia | `membroComumNaoLeNemPedeCopiaDoBanco` |
| dump por JDBC: tabela, INSERT, escape, estabilidade, retomada, verify recusando arquivo furado | `JdbcStandbyDumpSourceTests` (5) |
| dump é gerado uma vez por janela; retomada não regenera; verify recusa | `StandbyCacheTests` (4, fonte que muda a cada chamada) |
| nó relata falha de restauração; retomada usa o mesmo dump | `NetworkStandbyTests.noReportaFalhaERetomadaUsaOMesmoDumpEmCache` |
| o dump CARREGA num MariaDB 11.4 e devolve texto/binário/NULL/timestamp idênticos | `scripts/tests/standby-dump-migration-test.sh` (15 asserções) |
| migração duas vezes em MariaDB 11.4 com dados | `scripts/tests/standby-migration-test.sh` (12 asserções) |

A migração é testada no banco real porque o upsert do início de cópia e o `char(64)` do digest são
exatamente o tipo de coisa que o H2 perdoaria.
# 21 — Nó de reserva: cópia do banco e promoção com backup (brief cooperativo §11/§12)

A promoção de autoridade (`06-contingencia.md` §4, V317) trocava **quem manda** e deixava a réplica
de destino **sem os dados** de quem mandava. Promover sem cópia é trocar o nome do controlador e
recomeçar do zero: o failover só existe se o nó de reserva tiver o estado para assumir. Este
documento descreve o que passou a existir (V360) e, na última seção, o que **não** existe.

## 1. O desenho

Quem lê o banco é o Control Plane. O nó de reserva **recebe os bytes** do dump, confere o digest e
guarda. A credencial de leitura do banco não sai do Control Plane — um nó de participante com usuário
de replicação seria um segundo caminho de leitura do dado pessoal de todo mundo.

```
admin ──POST /admin/network/authority/standby/copy {nodeId}──▶ Control Plane  (linha SYNCING, V360)
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

O job é **desligado por padrão** (`rede.network.standby.auto-sync.enabled=false`). O dump exige o
binário e a senha do banco; um job que consulta o banco em desenvolvimento sem nó de reserva nenhum
só gastaria I/O. Ligado, o desenho fica completo.

## 3. O dump

`CommandLineStandbyDumpSource` (`rede.network.standby.dump.enabled=true`):

| opção | por quê |
|---|---|
| `--skip-dump-date` | duas cópias do MESMO dado têm o mesmo digest; sem isso, a retomada não provaria nada |
| `--single-transaction` | dump consistente sem travar a escrita do banco que atende |
| `--no-tablespaces` | não pede privilégio além de leitura |
| `MYSQL_PWD` no ambiente | a senha não aparece em `ps` |
| timeout (`dump.timeout-seconds`, mínimo 30 s) | dump travado não segura o endpoint indefinidamente |

Sem o dump ligado, o bean de fallback **não finge cópia**: devolve 503 e a linha fica `FAILED` com o
motivo. Um dump de mentira faria um nó sem o banco aparecer como pronto para assumir.

**Falha dentro da transação.** Quando o servidor não consegue ler o banco, a exceção sobe e a
transação da cópia sofre rollback. A marcação de falha é gravada por `StandbyFailureRecorder` em
transação própria (`REQUIRES_NEW`) — senão a linha voltaria a `SYNCING` e o painel diria "copiando"
para sempre.

### 3.1 O runtime do reserva já não fica para trás

A promoção exigia duas coisas do nó que assume, e o ciclo entregou as duas:
a **cópia do banco** (este documento) e a **versão corrente do runtime** — o
outro lado do mesmo problema: nada na máquina chamava o `--update` quando saía
release, e o master recebia deploy enquanto a reserva ficava no código antigo.
Agora `bootstrap.sh --auto-update on` faz o host seguir o release publicado
(`09-instalacao.md` §6.1, desligado por padrão). O que continua manual é a
cópia do banco, nas lacunas abaixo.

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
   servidor mandou), não que o dump abre. Uma cópia íntegra de um banco quebrado passaria.
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
| migração duas vezes em MariaDB 11.4 com dados | `scripts/tests/standby-migration-test.sh` (12 asserções) |

A migração é testada no banco real porque o upsert do início de cópia e o `char(64)` do digest são
exatamente o tipo de coisa que o H2 perdoaria.
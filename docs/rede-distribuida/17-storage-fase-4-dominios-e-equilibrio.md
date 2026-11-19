# Énóis Storage — fase 4: domínios de falha e equilíbrio da distribuição

Documento do que **existe** no código. Complementa
[`13-storage-fase-3-replicacao.md`](13-storage-fase-3-replicacao.md) (réplica, digest, varredura),
que continua valendo: nada da fase 3 foi reescrito.

A fase 4 do brief pede Placement Engine, rebalanceamento, storage classes, distribuição geográfica
e erasure coding. Esta rodada entrega **a parte do Placement Engine que faltava e a medição que
precede o rebalanceamento** — e diz explicitamente o que não existe.

## 1. Domínios de falha: a região entra na escolha

Até aqui a colocação sabia **não repetir o nó** ("duas cópias no mesmo disco não sobrevivem à perda
do disco"). Duas cópias em nós diferentes da **mesma região** caíam juntas quando a região caía: o
mesmo erro, um nível acima.

Agora `StoragePlacementService` decide em **duas passadas sobre a mesma ordem** (mais folga
primeiro):

1. uma cópia por região ainda não usada — inclusive as regiões que **já têm** cópia confirmada;
2. esgotadas as regiões distintas, completa com as repetidas.

| situação | resultado | por quê |
|---|---|---|
| há região distinta livre | vai para ela, mesmo com menos disco | diversidade vale mais que folga |
| não há região distinta | completa na repetida | segunda cópia na mesma região ainda é melhor que nenhuma |
| nó sem região declarada | domínio **desconhecido** | não é igual à região de outro, nem à de outro sem região |

A comparação usa `NetworkRegions.normalize` — a mesma de evacuação e afinidade. `"Sudeste"`,
`"sudeste"` e `"SUDESTE"` são uma região só.

**O que o domínio NÃO é.** Host físico, operadora e rack não estão no cadastro. Derivá-los (do
`display_name`, do IP, do dono) seria deduzir topologia a partir de texto livre — e um domínio errado
é pior que nenhum, porque faz o sistema acreditar em uma redundância que não existe.

## 2. Equilíbrio: medir antes de mover

`GET /admin/network/nodes/storage/balance` (admin) — `StorageBalanceReport`:

| campo | significado |
|---|---|
| `nodes[].confirmedCopies` | cópias **confirmadas** (`OK`) no nó |
| `nodes[].copiesSharePercent` | participação do nó nas cópias totais |
| `nodes[].diskFreeMb` | disco livre **medido** (`null` = não medido) |
| `nodes[].capacitySharePercent` | participação do nó na capacidade pronta |
| `nodes[].skewPp` | cópias − capacidade, em pontos percentuais |
| `skewed` | nós com desvio acima de **15 p.p.** |
| `measurable` | `false` quando não há régua (nenhum disco medido, ou nenhuma cópia) |

**A régua é a folga medida, não o declarado.** Um nó que declarou 1 TB e tem 10 GB livres é
justamente quem não deve receber mais — é a mesma regra do `store` (507 `STORAGE_NO_DISK_SPACE`).

**Só cópia `OK` conta.** Designada e não confirmada não ocupa disco; contá-la mandaria mover dados
para aliviar um nó que não está cheio.

**Sem medida, `measurable=false`.** `diskFreeMb`, `capacitySharePercent` e `skewPp` ficam **nulos**
(nunca zero: zero diria "sem espaço", o oposto de não sabido), o nó continua no relatório com as
cópias que tem, e `skewed` fica vazio — dizer "equilibrado" seria afirmar o que não foi medido.

**Não move nada.** Mover cópias é transferência pela rede de quem participa; a decisão depende de
banda, janela e prioridade (brief §16), que são escolha humana. O relatório entrega o "se" e o
"quem"; o "quando" e o "quanto" não foram implementados.

## 2.1 A colocação decide pela folga **cedida** (§8)

Até a fase 4, `StoragePlacementService` ordenava e filtrava candidato pelo **disco livre** da
máquina. Para o participante do §7 (2 TB de volume, 1,5 TB cedidos), o livre nunca chega a barrar:
com 1,5 TB ocupados o disco ainda mede 500 GB, a colocação continua designando cópia, e o Énóis
passa a ocupar mais do que a pessoa autorizou — o §8 pede "capacidade real dos participantes".

Agora a folga é **cedida**: `declarado − ocupado`, com `ocupado = max(cópias OK somadas, uso medido
pelo nó)`. O disco livre continua sendo régua, junto: ceder 50 GB num disco com 500 MB não é espaço.

| situação | colocação | `store` |
|---|---|---|
| folga cedida ≥ piso (1 GiB) + a cópia | designa | grava |
| folga cedida < piso | não designa | **507 `STORAGE_NO_CEDENT_SPACE`** com cedido, ocupado e piso |
| disco livre < piso | não designa | 507 `STORAGE_NO_DISK_SPACE` |
| declarado ausente (teto `0`) | **não designa** | 507 `STORAGE_NO_CEDENT_SPACE` |

O último caso é a mudança de contrato desta rodada: antes, "não medir não é saber que não cabe"
mandava ser permissivo. A medida que faltava não era o disco — era o número que só o participante
pode dar, e nó sem teto recebia cópia sem limite nenhum, contra o §7 ("não permitir que ...
consuma além do limite"). Nó de storage precisa de `declared_disk_mb` no enrollment.

`ocupado` usa o **maior** entre o confirmado no catálogo e o uso que o nó mediu em si (V344): os
dois são limites de baixo do uso real, e o menor esconderia o espaço que um deles já viu ocupado
(cópia interrompida deixa arquivo no volume sem estar confirmada).

## 3. O que NÃO está entregue

| item | por quê |
|---|---|
| mover cópias de fato (rebalanceamento ativo) | exige o corpo — hoje o Control Plane serve por digest e o nó busca; mover exige banda controlada, pausa/retomada e janela. Projeto próprio |
| host físico / operadora / rack como domínio | não estão no cadastro; derivar seria deduzir |
| storage classes (hot/cold, custo, durabilidade por classe) | o catálogo não tem classe de objeto; acrescentar exige decisão de produto (quem escolhe a classe de um objeto novo?) |
| erasure coding | muda o modelo de bytes+sha256 por objeto; hoje a base é réplica inteira |
| escolha por região na **tarefa** | já existe para despacho (`NetworkRegions`); no Storage só entrou na colocação |
| velocidade de leitura/escrita e IOPS estimados (§7) | **não se estimam.** O brief é explícito no §8.3: indicadores refletem o coletado, nunca o estimado. Medir de verdade exige benchmark no disco do participante — que é carga de escrita num nó de terceiro — e o benchmark precisa de desenho (tamanho, duração, horário, quando repetir). Decisão de produto/infra |
| estado dos discos (§7, SMART) | o runtime roda em contêiner sem acesso ao dispositivo; `smartctl` na máquina de um voluntário é requisição de confiança que hoje a rede não tem motivo para fazer. Decisão de produto |

Capacidade (§7) **está entregue** por nó em `GET /admin/network/nodes/storage/overview`: `disks[]`
com `declaredMb`, `freeMb`, `totalMb`, `reservedMb`, `usedMb` e `usedPercent`.

## 4. Verificação

| verificação | resultado |
|---|---|
| `StoragePlacementServiceTests` | **13** (8 da fase 3 + 5 novos: duas réplicas na mesma região; usa a mesma quando não há outra; região não declarada ≠ declarada; não repete a região que já tem cópia; grafia não cria domínio) |
| `StorageBalanceReportTests` | **6** — desequilíbrio apontado; proporcional não aponta ninguém; `STALE` não conta; sem medida não afirma nada; nó sem medida fica com capacidade nula; sem nós não inventa base |
| `NetworkStorageBalanceTests` | **3** — rota exige admin; sem nó não inventa base; disco não medido chega nulo (não zero) |
| `StorageReplicationDispatcherTests` | **3** — o teto da transferência vai no payload (`toReplica`, não `fromReplica`); sem transferência o campo não vai; transferência de outra réplica não define o teto desta |
| `node-executor-test.sh` (harness) | **209** asserções (186 + 23 desta rodada): 8 Mbps do payload vira `limit-rate 1000k`; teto acima do declarado usa o do nó (`12500k`) |

Mutações conferidas:

| mutação | derruba |
|---|---|
| diversidade de região desligada (uma passada) | `naoDesignaDuasReplicasNaMesmaRegiao`, `naoRepeteARegiaoQueJaTemCopia` |
| sem a 2ª passada (só região distinta) | 6 testes de colocação |
| contar colocação `STALE` | `copiaNaoConfirmadaNaoContaNoRelatorio` |
| disco não medido virar `0` | 2 erros nos testes do relatório |
| usar `declared_disk_mb` em vez do medido | 3 testes do relatório |
| tirar `bandwidthLimitMbps` do payload (§12.5) | `o_teto_da_transferencia_vai_no_payload_do_object_put` |
| runtime ignorar o payload da tarefa (`bandwidth_args "$config"` sem o segundo argumento) | `o teto da transferencia vale no download do corpo` (harness) |

Nenhuma migração nova: a fase 4 usa o catálogo e as colocações que a fase 1/3 já tinham.

## 5. Defeitos encontrados no caminho

- **A região ocupada nunca era lida.** O filtro de "nós que já têm cópia" rodava antes de extrair a
  região deles, então `takenRegions` ficava vazio e a segunda cópia caía na mesma região — o teste
  `naoRepeteARegiaoQueJaTemCopia` pegou.
- **`-1` como "não medido" no relatório.** A primeira versão devolvia `-1` para disco/capacidade não
  medidos; isso ordena o nó como se estivesse cheio e é justamente o `null ≠ 0` que o projeto trata
  como regra. Trocado por nulo, com o nó indo para o fim da lista, não para o topo.
- **A rota colidia com `/{id}`.** A classe é mapeada em `/admin/network/nodes`, então
  `/storage/balance` ficou sob `/nodes/storage/balance`; o teste de autorização viu `200` em vez de
  `401` porque `/{id}` respondia.
## 6. Tráfego e disponibilidade do nó (brief §18, V345)

O §18 pede que o participante **consulte o tráfego** e a **disponibilidade** da máquina que cede.
Os dois números têm origens diferentes, e misturá-los seria inventar um deles.

**Tráfego: medido pelo nó.** `bytesInMb`/`bytesOutMb` vêm no heartbeat, acumulados nas interfaces do
próprio nó (V345). O Control Plane só os guarda — contar bytes servidos do lado dele mediria o que o
Control Plane transfere, não o que a máquina do participante consome da banda dele, e é a banda dele
que o §18 quer mostrar. Nulo é "não mediu" (runtime anterior); zero é "medido, nada transferido".
A proporção de saída (`outboundSharePercent`) é calculada aqui, nunca enviada pelo nó — senão um nó
poderia dizer 90% de saída com 1 GB contra 10 GB de entrada.

O mesmo número aparece no **painel de admin**: `GET /admin/network/nodes` devolve `trafficInMb` e
`trafficOutMb` por nó (campos que o `NetworkNode` já carregava e o `NodeView` não expunha), e a linha
do nó mostra entrada e saída — `null` como "sem medição", nunca zero.

**Disponibilidade: medida pela rede.** A rede registra *transições*, não cada heartbeat (uma linha
por ciclo por nó afogaria a trilha — ver `registrarReconexao`). O que ela consegue afirmar é o que a
trilha prova: quantas vezes o nó voltou de uma ausência (`outages`, contados em
`NETWORK_NODE_RECONNECTED`) e por quanto tempo ficou fora (`offlineSeconds`, somado dos
`offlineForSeconds` de cada linha).

Por isso `offlineSeconds` é acompanhado de dois campos:

| campo | o que diz |
|---|---|
| `measuredOutages` | quantas das quedas entraram na soma |
| `availabilityComplete` | `false` quando a soma é sobre **menos** quedas do que existem — a amostra de 200 linhas parou antes do fim, ou a linha não tinha o intervalo |

`availabilityComplete=false` quer dizer que `offlineSeconds` é um **piso**, não o tempo fora real. A
tela que apresentar o número como total estaria dizendo que o nó foi mais disponível do que foi.

Linha de reconexão sem `offlineForSeconds` conta como queda — ela prova que houve uma — e não entra
na soma: somar zero diria "voltou no mesmo instante", que é outra afirmação. Pelo mesmo motivo ela
não vira `lastOfflineSeconds`.

`online` usa a mesma janela de "vivo" do painel (`rede.network.node-stale-seconds`, 180 s): um nó
"online" na tela do participante e "fora" no painel seriam dois números para o mesmo fato.

**O que não existe.** Disponibilidade ao longo de um período (servido/parado por faixa de tempo),
latência de tráfego, IOPS e velocidade — dependem de série histórica que o módulo não guarda além da
amostra de observabilidade do último minuto, e de decisão de produto (§7 do brief).

## 7. O participante ajusta o que cede (brief §18, sem migração)

O §18 lista "definir capacidade máxima" e "definir limites de banda" entre o que o **participante**
faz. Até aqui só o admin conseguia (`POST /admin/network/nodes/{id}/capacity`): o participante
precisava de um terceiro para emprestar mais — ou menos — da própria máquina.

`POST /network/participant/nodes/{id}/capacity` fecha isso.

| aspecto | o que vale |
|---|---|
| escrita | a **mesma** do admin (`NetworkNodeService.capacity`) — não existe caminho paralelo de configuração |
| efeito | soma 1 em `configVersion`; o runtime aplica na próxima leitura da config assinada |
| campo ausente | mantém o valor atual |
| negativo | `400 NODE_CAPACITY_INVALID` |
| perfil | **não** muda por esta rota — perfil é permissão de tipo de tarefa e continua decisão administrativa |
| dono | conferido no **serviço** (404, nunca 403) |
| auditoria | `NODE_CAPACITY_CHANGED_BY_PARTICIPANT`, com os quatro limites no metadado |

O participante pode **subir e descer** os próprios limites. Um teto que só o admin sobe faria o
participante pedir licença para emprestar mais da própria máquina, e o brief diz que ele "deverá
conseguir controlar a utilização de sua infraestrutura". O que separa os dois atores é o perfil:
tirar ou dar perfil muda **quais tarefas** o nó pode receber, e isso é aprovação humana.

## 8. Tipos de storage node (brief §6, V348)

O §6 pede seis perfis especializados — `STORAGE_NODE_STANDARD`, `_MEDIA`, `_ARCHIVE`, `_EDGE`,
`_REPLICA`, `_HOME` — e diz que "um mesmo servidor poderá disponibilizar múltiplos perfis,
respeitando os limites e permissões autorizados". Antes desta rodada havia um só: `NODE_STORAGE`.

### 8.1 Perfis

`NodeProfile` ganhou os seis nomes do contrato. `NODE_STORAGE` continua existindo e é o tipo
**STANDARD**: todo nó cadastrado antes tem só ele, e nada muda para ele.

| perfil | `storageKind()` | para que serve |
|---|---|---|
| `NODE_STORAGE` | `STANDARD` | convencional, leitura e escrita, qualquer objeto |
| `NODE_STORAGE_STANDARD` | `STANDARD` | mesmo perfil, com o nome do brief |
| `NODE_STORAGE_MEDIA` | `MEDIA` | imagens, vídeos e miniaturas |
| `NODE_STORAGE_ARCHIVE` | `ARCHIVE` | longo prazo: capacidade e custo |
| `NODE_STORAGE_EDGE` | `EDGE` | perto dos usuários: leitura e distribuição |
| `NODE_STORAGE_REPLICA` | `REPLICA` | mantém cópias redundantes |
| `NODE_STORAGE_HOME` | `HOME` | servidor doméstico, disponibilidade variável, NAT |

**Permissão**: `OBJECT_PUT` e `OBJECT_GET` são autorizados por **qualquer** perfil com
`storageKind()` não nulo — a especialização é aditiva. Quem tem só `NODE_STORAGE` continua
recebendo tudo. Perfil sem `storageKind()` (`NODE_WORKER`, `NODE_EDGE`…) continua sem autorizar
armazenamento, e nenhum perfil de storage autoriza tarefa que não seja de armazenamento.

### 8.2 Preferência de colocação — preferência, nunca filtro

A ordem de candidatos passou a ter três critérios, nesta prioridade:

1. **o tipo do objeto**: nó que declara o tipo preferido vem antes;
2. **folga cedida** (`declarado − ocupado`), a mesma régua do §2.1;
3. `id`, para a ordem ser estável.

A classe do objeto é derivada do que o catálogo já sabe (`ObjectClass`), sem coluna nova:

| classe | como é decidida | tipo preferido |
|---|---|---|
| `MEDIA` | `contentType` começa com `image/`, `video/` ou `audio/` | `MEDIA` |
| `COLD` | `createdAt` mais velho que `rede.storage.cold-after-days` (padrão 30) | `ARCHIVE` |
| `DEFAULT` | o resto, e objeto fora do catálogo | `STANDARD` |

**"Frio" é piso, não verdade**: o catálogo não guarda último acesso, então a idade é medida desde
`createdAt`. Um objeto criado há 60 dias pode ter sido lido ontem — por isso a colocação
**prefere** o arquivo e não exige.

Sem nó do tipo preferido, qualquer nó de armazenamento recebe: exigir o tipo deixaria objeto sem
réplica por falta de um perfil que é declarativo, e a durabilidade do §9 cairia por topologia.
Nó com vários tipos casa com qualquer um deles.

### 8.3 Banda por tipo

| aspecto | o que vale |
|---|---|
| escrita | `POST /network/participant/nodes/{id}/storage-bandwidth/{kind}` com `{"mbps":15}` |
| remoção | `mbps` nulo; o tipo volta a usar a banda geral do nó |
| tipo não declarado pelo nó | `409 STORAGE_KIND_NOT_DECLARED` |
| tipo desconhecido | `400 STORAGE_KIND_INVALID` |
| negativo | `400 NODE_CAPACITY_INVALID` |
| efeito | soma 1 em `configVersion` (`NetworkNodeRepository.touchConfig`, que **não** reescreve os limites) |
| persistência | `network_node_storage_bandwidth`, uma linha por (nó, tipo) — V348 |
| ausência | o tipo usa `limits.bandwidthMbps` do nó; **nunca zero** |
| auditoria | `NODE_STORAGE_BANDWIDTH_CHANGED` / `_REMOVED` |

### 8.4 Formato 7 da config assinada

`FORMAT_VERSION = 7`. O campo novo vai **no fim** da linha canônica:

```
...|diskFreeMb|diskTotalMb|["ARCHIVE=5","MEDIA=15"]
```

Lista ordenada por nome de tipo, no formato `TIPO=mbps`. Config de formato ≤ 6 continua
verificável: `canonical()` **omite** a lista quando `formatVersion <= 6`, então a assinatura de um
documento antigo não muda. O runtime (`deploy/node-runtime/node-runtime.sh`) reconstrói a linha do
mesmo jeito e só acrescenta a lista quando o formato é ≥ 7.

Na transferência, o runtime aplica o teto **do tipo** sobre `limits.bandwidthMbps` (o tipo reservou
aquela banda; aplicar a geral seria ceder mais do que o participante aceitou). Nó com vários tipos
usa o **menor** teto entre eles. Harness: `scripts/tests/node-executor-test.sh`, cenário 25b —
inclui o caso de lista adulterada depois de assinada, que tem de ser recusada.

### 8.5 O que NÃO está entregue

- **`HOME` é declarativo, sem regra própria.** Um nó doméstico não recebe tratamento de
  durabilidade diferente: a colocação o trata como qualquer storage, e a conta de domínio de falha
  do §1 não o distingue. O perfil serve para o participante declarar a natureza da máquina e para
  o painel mostrá-la. Diferenciar (nunca ser única cópia de um domínio, menos réplicas, peso menor)
  é decisão de produto e não foi tomada.
- **`EDGE` e `REPLICA` não mudaram nada no comportamento.** Não há objeto cujo tipo preferido seja
  `EDGE` ou `REPLICA`: nó desses tipos recebe por folga, como o STANDARD. O brief descreve o que
  eles *priorizam*, e não há ainda medida (latência por usuário, papel de redundância) que decida a
  preferência.
- **CDC do Cooperative (§4)** continua bloqueado por decisão de infra.
- **Sem IOPS, SMART ou disponibilidade por faixa** (§7): o que existe é disco livre, total, uso de
  conteúdo e tráfego medidos pelo nó.

## 9. IP de origem e mudança de endereço (brief §5, V350)

O §5 lista o que o participante doméstico **não** deve precisar fazer: contratar IP público,
configurar DNS, abrir portas, informar IP no painel. A consequência é que a rede não deve
**perguntar** o endereço — ela o **observa** na borda do heartbeat.

| coluna | o que é |
|---|---|
| `observed_ip` | último endereço de origem visto; nulo é "não medido" |
| `observed_ip_changed_at` | instante da última **mudança**; nulo é "nunca mudou desde o primeiro contato" |

Regras, todas provadas por teste:

1. **Primeiro contato não é mudança** — grava o IP e deixa o instante nulo (houve conhecimento,
   não troca).
2. **O mesmo IP não move o instante** — nó doméstico bate coração a cada ciclo; reescrever a marca
   a cada vez faria o painel dizer "mudou há 15 segundos" para sempre. Mesmo princípio da
   reconexão (§8.6): registra-se a **transição**, não a amostra.
3. **`X-Forwarded-For` vence o socket**, e o primeiro item da lista é o cliente: atrás do proxy, o
   socket fala com o proxy, não com o nó.
4. Sem nenhum dos dois, devolve nulo e **nada é gravado** — nunca um endereço inventado.

O IP entra pelo **transporte**, não pelo corpo: um campo no JSON seria o nó afirmando o próprio
endereço, o oposto de medir.

### O que NÃO está entregue

- **Reverse tunnel**: o brief manda "reutilizar o mecanismo de Reverse Tunnel existente", e ele
  **não existe**. O modelo é pull curto (`GET /network/tasks/next` a cada `NODE_POLL_SECONDS`).
  Isso já atende NAT (o nó só faz saída), mas não permite ao Control Plane **empurrar** trabalho.
- **Multiplexação, renovação de credenciais, transferências retomáveis, recuperação de sessões**:
  inexistentes. `NODE_POLL_SECONDS` fixo, sem backoff; a chave do nó não expira nem roda.
- A detecção de IP **registra**, não age: não há política que reaja a uma troca de endereço.

## 10. Entrega e distribuição (brief §13)

O §13 pede cache HTTP, `ETag`, `Last-Modified`, `Range`, entrega por streaming e URLs assinadas. A
entrega por **streaming** já era a do `StorageCatalogService` (o corpo sai do disco da instalação, em
pedaços, e nunca é carregado inteiro); esta seção acrescenta o que faltava para um cliente HTTP
aproveitá-la.

### 10.1 Duas rotas, duas políticas opostas

| rota | para quem | `Cache-Control` | `Range` |
|---|---|---|---|
| `GET /network/storage/objects/{id}` | nó autenticado (`X-Node-Key`) | `private, no-store` | não |
| `GET /network/storage/objects/{id}/content` | cliente (pública, com sessão ou sem) | pelo **tipo** do objeto | sim |

Separar foi decisão, não estética. Servidas pela mesma rota, a política teria de depender de "quem
pediu", e um cabeçalho esquecido no meio tornaria pública uma imagem privada. A rota do nó continua
como era, com `Last-Modified` acrescentado.

### 10.2 A política vem do conteúdo, não do caminho

`CachePolicy.forContentType`:

| content-type | `Cache-Control` |
|---|---|
| `image/*`, `video/*`, `audio/*` | `public, max-age=31536000, immutable` |
| `application/pdf`, `text/plain`, qualquer outro | `private, no-store` |
| nulo (desconhecido) | `private, no-store` |

O padrão é **privado**: um tipo que ninguém classificou não vira cacheável por omissão. `immutable`
só é honesto porque o objeto do catálogo é imutável — conteúdo novo é objeto novo, com id próprio,
que nunca muda por baixo de um cache. A rota do nó usa `CachePolicy.NODE`, sempre `no-store`, mesmo
quando o tipo é imagem.

### 10.3 `ETag` e `Last-Modified`

- **`ETag`** é o `sha256` do conteúdo entre aspas. O digest **é** a identidade do conteúdo, então uma
  comparação de `If-None-Match` é exata, não uma amostra.
- **`Last-Modified`** é o `updated_at` da linha do catálogo.
- `If-None-Match` bate (ou é `*`) → **304**, sem corpo, com `ETag` e a mesma política de cache. A
  comparação é fraca (`W/` ignorado) e aceita lista, como a RFC 9110 manda.

### 10.4 `Range`

`ByteRange.parse` aceita `bytes=a-b`, `bytes=a-` (o que o player manda ao retomar) e `bytes=-n` (os n
últimos). Regras:

| entrada | resposta |
|---|---|
| faixa válida | `206` + `Content-Range: bytes a-b/total` + o trecho |
| fim além do objeto | **truncado** até o último byte (`206`), como a RFC manda |
| início além do objeto | `416` + `Content-Range: bytes */total` |
| vários intervalos (`bytes=0-1,5-6`) | corpo inteiro (`200`) |
| outra unidade, malformado, ausente | corpo inteiro (`200`) |
| objeto vazio | corpo inteiro (`200`) |

Toda resposta `200`/`206` traz `Accept-Ranges: bytes`. A faixa é lida **do disco** (`skip` + cópia
limitada): nunca se carrega o arquivo inteiro em memória para servir 5 bytes.

**Por que 416 e não "serve tudo".** Servir o corpo inteiro em silêncio para uma faixa que não existe
faz o player achar que recebeu o trecho pedido e tocar do começo — o "vídeo que reinicia", um bug pior
do que um erro visível. Múltiplos intervalos são a exceção porque montar `multipart/byteranges` à mão
tem risco próprio, e cair no corpo inteiro é sempre uma resposta correta.

### 10.5 Consumidores

O front já **depende** de `Range`/`206` para vídeo: `withImageKitOriginal` pede o arquivo original do
ImageKit porque a entrega transformada daquele CDN recusa `Range` com `403` quando a cota de
transcodificação estoura. Teste em `enois-frontend/src/lib/api/media.test.ts` fixa que a URL do
nosso Storage passa intacta pelo adapter (nenhum `?tr=` inventado) — a rota nova serve `Range`
nativamente e não precisa de transformação.

O player do design system (`lite-video.tsx`, e o `<audio>` do `media-grid`) usa `<video>`/`<audio>`
com `preload="metadata"`, que faz `Range` sozinho. Teste em
`enois-design-system/src/domain/post/media/storage-delivery.test.tsx` prova que a fonte continua
sendo o elemento nativo e não vira `blob:` — um `fetch` que monte `blob:` perderia o seek e o cache
HTTP.

### 10.6 O que NÃO está entregue

- **URLs assinadas e expiração de links** — a rota `/content` é pública para objeto servível. Não há
  segredo por objeto nem prazo. Sem isso, imagem privada depende de a URL não circular: a política de
  cache a protege do proxy, não de quem tem o link.
- **Invalidação de cache**: o `immutable` de um ano assume o objeto imutável. Não existe `PURGE` nem
  versão de URL a invalidar, porque não haverá o que invalidar enquanto objeto não for reescrito.
- **CDN e nós EDGE servindo imagem**: `CachePolicy` torna a resposta cacheável; não há integração
  com CDN nem rota de EDGE.
- **Processamento de imagem** (redimensionar, recortar) continua no ImageKit; a rota nova serve o
  original.
- **Erasure coding (§9)**: decidido **não implementar** nesta fase; continua replicação completa,
  verificada pela varredura (§15).

## 11. Versionamento e ciclo de vida (brief §14, V352)

Antes desta seção: a chave `(bucket, object_key)` era única, então `PUT` na mesma chave devolvia o
objeto antigo (retry idempotente — certo para rede instável, errado para "guardei nova versão"), e
nada tinha prazo. Remover era marcar lápide; a varredura de lápide já existia (fase 3). O que faltava
era **o que fazer com o que sobra**: versões e prazo.

### 11.1 A versão é um objeto com id próprio

Cada versão de uma chave é uma linha do catálogo com `id` próprio. O `id` que um cliente já recebeu
**continua devolvendo o conteúdo que ele gravou** — a versão nova não herda esse nome. É o que
permite uma URL de mídia continuar estável depois de o arquivo ser substituído.

| coluna | o que é |
|---|---|
| `version` | 1, 2, 3… por chave; todo objeto que já existia é a versão 1 de si mesmo |
| `superseded_by` | id da versão que tomou a frente; **nulo é a corrente** |
| `expires_at` | prazo calculado na gravação; nulo é "não expira" |

`find(bucket, chave)` resolve para a corrente. O histórico é a lista inteira.

### 11.2 Unicidade: só a corrente é única

MariaDB não tem índice condicional. A condição vira **coluna gerada**: `current_key` devolve
`bucket + char(31) + chave` na versão corrente e **NULL** na substituída — e NULL não colide em
índice único. O índice `uq_network_storage_objects_current` é `(bucket, object_key, current_key)`:
as duas primeiras colunas mantêm a chave como alvo válido do `on duplicate key update` da inserção
idempotente.

Consequência direta, provada no MariaDB real: **duas versões correntes da mesma chave são
recusadas pelo banco**, não por código de aplicação. É a garantia que dois processos gravando a mesma
chave ao mesmo tempo precisam.

### 11.3 Política por bucket

`network_storage_buckets`, uma linha por bucket configurado. Sem linha, valem os padrões do código:
**sem versionamento, sem prazo** — criar a tabela não muda nada de quem não configurou.

| campo | efeito |
|---|---|
| `versioning` | cada gravação concluída é versão nova; falso mantém o retry idempotente |
| `retain_superseded_days` | dias que uma versão **substituída** sobrevive; nulo guarda para sempre |
| `expire_after_days` | dias que um objeto **corrente** vive desde a gravação; nulo não expira |

Retenção e expiração são campos diferentes porque contam de instantes diferentes: expiração conta da
**gravação**, retenção conta da **substituição**. Retenção num bucket sem versionamento é recusada
com 400 — seria configuração que nunca age.

### 11.4 Expiração vale na leitura, não só na varredura

Objeto vencido responde **404** imediatamente, antes de qualquer varredura. Expiração que só vale
quando a varredura passa é um campo na tabela, não expiração: entre o vencimento e o próximo ciclo, o
arquivo continuaria sendo servido.

A varredura (`POST /admin/network/storage/lifecycle/run`, `dryRun=true` por padrão) faz duas coisas,
e as duas **marcam lápide, nunca apagam linha**:

1. **expiração** — objeto corrente, servível e vencido vira `DELETED`;
2. **retenção** — versão substituída além do prazo do bucket vira `DELETED`.

A linha tem de continuar existindo: é ela que a coleta de lápides da fase 3 percorre para mandar
cada nó soltar as cópias. Apagar a linha deixaria o disco do participante sem registro do que
coletar.

### 11.5 Restaurar é gravar de novo, não voltar o passado

`POST /admin/network/storage/objects/restore` cria uma **versão nova** com o conteúdo da antiga. A
versão que estava na frente é substituída, não apagada: quem baixou a versão corrente continua com o
que baixou, e o histórico continua contando o que aconteceu. Restaurar a versão que já está na frente
é 409, não operação vazia.

O digest restaurado é o que o catálogo guardava da versão antiga — a restauração não relê o disco.

### 11.6 Rotas (admin)

| rota | o que faz |
|---|---|
| `GET /admin/network/storage/buckets/{bucket}/policy` | política vigente; sem linha, devolve os padrões com `configured=false` |
| `PUT /admin/network/storage/buckets/{bucket}/policy` | grava versionamento, retenção e expiração |
| `GET /admin/network/storage/objects/versions?bucket=&key=` | histórico, do mais antigo ao mais novo |
| `POST /admin/network/storage/objects/restore` | restaura versão como versão nova |
| `POST /admin/network/storage/lifecycle/run?dryRun=` | expiração e retenção |

### 11.7 O que NÃO está entregue

- **Coleta de lápide de versão disparada pelo ciclo de vida**: ela marca a lápide e deixa a coleta
  para o coletor da fase 3 (`retention-hours` próprio). Não há agendamento automático; as varreduras
  rodam por chamada.
- **"Um nó desconectado não restaura silenciosamente um arquivo excluído"** é garantido na
  **confirmação**: upload que termina depois da lápide não ressuscita o objeto
  (`where state = 'PENDING'`), e nó que volta não recebe tarefa de um objeto `DELETED`. Não existe
  protocolo de sincronização de estado entre nó e Control Plane — o nó não mantém catálogo próprio.
- **Expiração por prefixo ou por objeto**: o prazo é só por bucket.
- **Versionamento retroativo**: os objetos que existiam antes são a versão 1; não há reconstrução de
  histórico que nunca foi gravado.

## 12. Rebalanceamento (brief §16, V354)

A §2 desta doc mede o desequilíbrio e diz explicitamente: "não move nada". Esta seção entrega o
movimento, com a regra que manda no desenho escrita no brief:

> Nunca remover a última cópia saudável antes de confirmar a persistência e integridade do destino.

### 12.1 Três passos, e só o terceiro toca a cópia antiga

| passo | rota | o que muda no catálogo |
|---|---|---|
| 1. designar | `POST /admin/network/storage/rebalance` | réplica nova nasce `PENDING_REMOTE`; **antiga continua `OK`** |
| 2. confirmar | `PUT /network/storage/objects/{id}/replica` (rota que já existia) | destino declara o que gravou; o Control Plane confere digest e tamanho |
| 3. concluir | `POST /admin/network/storage/rebalance/{id}/complete` | **só com o destino `OK`** a antiga vira `GONE` |

Consequência direta: a migração é **sem indisponibilidade**. Entre o passo 1 e o 3 a cópia antiga
serve normalmente — a página que aponta para ela não para no meio da transferência.

### 12.2 O que o movimento recusa (e por quê)

| situação | resposta | motivo |
|---|---|---|
| origem é a **única** cópia `OK` | 409 `STORAGE_REBALANCE_LAST_COPY` | mover a única é apostar o arquivo na transferência |
| origem não é `OK` (`STALE`, `GONE`, `PENDING_REMOTE`) | 409 `STORAGE_REPLICA_NOT_HEALTHY` | o conteúdo dela não é confiável |
| destino já tem cópia do objeto | 409 `STORAGE_REBALANCE_SAME_NODE` | não alivia ninguém e desvia da conta de domínio de falha |
| destino inativo ou sem perfil de storage | 409 `STORAGE_REBALANCE_DEST_NOT_READY` | a transferência nunca enfileiraria tarefa |
| já há transferência viva desta réplica | 409 `STORAGE_REBALANCE_IN_PROGRESS` | clique duplo não abre duas transferências do mesmo objeto |

O número da réplica nova continua o maior existente; o número da antiga **não é reaproveitado** pela
mesma razão do healing: a chave `(object_id, replica)` sobrescreveria a cópia que ainda está servindo.

### 12.3 Desfazer não toca a cópia antiga

`POST /admin/network/storage/rebalance/{id}/cancel` recolhe a designação nova (`GONE`) e deixa a
antiga como estava. Depois de `DONE` o cancelamento é **409**: desfazer o `DONE` removeria a cópia
nova depois de a antiga ter saído — o único caminho que deixaria o objeto sem nenhuma das duas.

`FAILED` é o estado de uma transferência que perdeu a designação (recolhida por outra rota, nó
removido): a antiga continua, e o operador vê o motivo em `failureReason`, em vez de uma
transferência viva para sempre.

### 12.4 Unicidade: uma transferência viva por réplica

A mesma coluna gerada do V352: `live_key` vale `object_id:from_replica` **só em `PENDING`** e NULL nos
demais estados. Com a chave composta `(object_id, from_replica, live_key)`, o banco recusa duas
transferências vivas da mesma réplica — e **aceita** duas concluídas ao longo do tempo, que é o que
uma unicidade ingênua por estado proibiria para sempre (a segunda recuperação do mesmo objeto ficaria
impossível).

### 12.5 Bandas e progressividade

O teto de banda é **por transferência** (`bandwidthLimitMbps`, padrão 10), não por nó: o operador
escolhe quanto ceder para um objeto específico.

O caminho dele até o nó tem três elos, e os três existem:

1. o `POST /admin/network/storage/rebalance` grava o teto na linha da transferência (V354);
2. o `StorageReplicationDispatcher` põe `bandwidthLimitMbps` no payload do `OBJECT_PUT` quando há
   transferência **viva** cuja réplica de destino é a desta designação — o vínculo é `toReplica`, não
   `fromReplica`: a transferência que constrói esta cópia é a que define o teto dela. Sem
   transferência o campo **não vai** (ausente = "usa o do nó", nunca zero);
3. o runtime (`bandwidth_args`, `exec_object_put`) aplica `--limit-rate` no **menor** entre o teto da
   transferência e a banda declarada (geral ou por tipo).

Antes do elo 2 e do elo 3, o campo era gravado e nunca lido: o operador pedia 8 Mbps e o nó baixava
na banda inteira declarada.

Não há transferência paralela de vários objetos numa mesma chamada: cada transferência é uma decisão
de operador com o próprio teto.

### 12.6 Rotas

| rota | o que faz |
|---|---|
| `GET /admin/network/storage/rebalance` | transferências vivas, com a antiga ainda no lugar |
| `POST /admin/network/storage/rebalance` | pede a transferência e enfileira o `OBJECT_PUT` |
| `POST /admin/network/storage/rebalance/{id}/complete` | confirma o destino e remove a antiga |
| `POST /admin/network/storage/rebalance/{id}/cancel` | desfaz; a antiga nunca é tocada |

### 12.7 O que NÃO está entregue

- **Escolha automática de quem mover**: a decisão de qual réplica sai continua do operador, a partir
  do relatório da §2. Não há agendador que abra transferências sozinho — cada uma é tráfego de
  corpo pela rede de quem participa.
- **Prioridade por importância** (§16): não há peso por bucket ou classe de armazenamento na ordem
  das transferências; a ordem é a escolha do operador.
- **Pausa e retomada no meio de uma transferência em curso**: desfazer recolhe a designação e
  recomeçar abre outra; não existe retomada de onde parou. O `OBJECT_PUT` do nó não é retomável
  (lacuna registrada na doc da fase 3).
- **Progresso em bytes**: o estado é por etapa (`PENDING`/`DONE`/…), não por fração transferida.
- **Teto de banda aplicado pelo próprio nó além do `--limit-rate`**: o nó limita a **velocidade**;
  não há reserva nem fila de banda entre transferências concorrentes (o runtime executa uma tarefa
  por ciclo, então hoje não há concorrência a arbitrar).
## 11. Disponibilidade histórica (brief §7, V369)

**Entregue.** O §7 pede "disponibilidade histórica" na página do nó; até o V369 ela era a última
linha da tabela do §3, porque a rede guardava **transições** (`NETWORK_NODE_RECONNECTED` na trilha)
e **amostras** de 1 minuto (V325, 60 pontos) — nenhuma das duas responde "ficou no ar 99,2% dos
últimos 7 dias".

### 11.1 A série

`network_node_uptime_daily`, uma linha por nó e por dia:

| coluna | significado |
|---|---|
| `present_seconds` | segundos do dia em que o nó esteve dentro da janela de "vivo" |
| `observed_seconds` | segundos do dia sobre os quais houve medida (entre o primeiro e o último heartbeat do dia) |
| `first_seen_at` / `last_seen_at` | o menor e o maior contato do dia (a janela não encolhe) |
| `received_at` | o fim do trecho que chegou **por último** — é contra ele que o reenvio é reconhecido |

A proporção é **uma soma sobre uma soma** (`100 × present / observed`), nunca a média das proporções
dos dias: um dia com uma hora de medida e um dia inteiro não pesam igual. Dia sem tempo observado
devolve `percentage: null` — "sem dado", não "0% no ar".

### 11.2 A regra do trecho

Entre dois heartbeats consecutivos o Control Plane sabe uma coisa: o nó respondeu no primeiro e no
segundo. O intervalo entre eles é **observado** inteiro, e conta como **no ar** até
`rede.network.node-stale-seconds` (180 s por padrão) depois do primeiro contato — a mesma janela de
"vivo" do painel e do scheduler, para não haver dois números sobre a mesma coisa. Depois dela, o
resto do intervalo é tempo fora.

- **Primeiro contato** não inventa trecho: o tempo antes dele não existia para a rede.
- **Trecho fora de ordem** (chegou depois, terminando antes do último contato) **soma** — é tempo
  observado que ainda não estava contado.
- **Reenvio** (o mesmo fim de trecho que chegou por último) **não soma**: é o mesmo heartbeat
  publicado de novo. A comparação é com `received_at`, a chegada, e não com `last_seen_at`, o maior
  instante — senão o reenvio de um trecho fora de ordem passaria como novo.
- O trecho vai inteiro para o **dia do último contato**, não é partido na meia-noite: partir exigiria
  saber se o nó estava no ar às 00:00, e o que se mediu foram dois contatos. A consequência é
  conservadora — uma ausência de três dias cai toda no dia da volta e não some da janela de 7 dias.

### 11.3 Rotas

| rota | quem | o que devolve |
|---|---|---|
| `GET /admin/network/nodes/{id}/uptime?days=7` | administrador | a série por dia, de 1 a 90 dias |
| `GET /network/participant/nodes/{id}/uptime?days=7` | dono do nó | a mesma série; nó de outra conta é 404 |
| campo `uptime` em `GET /network/participant/nodes` | dono do nó | a série de 7 dias de cada nó |

O admin e o participante leem a **mesma** função (`NodeUptimeService.last`), para não divergirem.

### 11.4 Defeito corrigido: a soma das ausências era um piso

`NetworkParticipantService.availability` somava `offlineForSeconds` das **200 linhas mais recentes**
de reconexão e comparava com o **total** de quedas. A partir da 201ª, `complete` virava `false` para
sempre e o tempo fora deixava de ser total — virava piso, sem nunca mais ser o número certo. Agora a
soma lê todas as linhas (`AuditLogRepository.allMetadataFor`, que projeta só o metadado) e
`complete` é sempre `true`; `measuredOutages` continua dizendo quantas quedas tinham o intervalo
gravado — são duas perguntas diferentes.

### 11.5 Retenção

`rede.network.uptime.retention-days` (365 por padrão) e a varredura diária
`rede.network.uptime.retention.cron` (`0 20 4 * * *`). A rota lê no máximo 90 dias; o resto é
retenção para quem quiser olhar o ano.

### 11.6 O que NÃO está entregue

- **"No ar" por serviço**: o contador mede o nó vivo, não se o processo que serve o Storage estava
  respondendo. É a mesma medida do painel; separá-las exige um sinal de saúde por perfil.
- **Disponibilidade por janela arbitrária** (um mês específico): a leitura é "últimos N dias até
  hoje". Consultar um intervalo passado exigiria outra forma de rota.

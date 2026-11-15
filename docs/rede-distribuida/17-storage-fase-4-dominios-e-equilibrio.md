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

**A régua é a folga medido, não o declarado.** Um nó que declarou 1 TB e tem 10 GB livres é
justamente quem não deve receber mais — é a mesma regra do `store` (507 `STORAGE_NO_DISK_SPACE`).

**Só cópia `OK` conta.** Designada e não confirmada não ocupa disco; contá-la mandaria mover dados
para aliviar um nó que não está cheio.

**Sem medida, `measurable=false`.** `diskFreeMb`, `capacitySharePercent` e `skewPp` ficam **nulos**
(nunca zero: zero diria "sem espaço", o oposto de não sabido), o nó continua no relatório com as
cópias que tem, e `skewed` fica vazio — dizer "equilibrado" seria afirmar o que não foi medido.

**Não move nada.** Mover cópias é transferência pela rede de quem participa; a decisão depende de
banda, janela e prioridade (brief §16), que são escolha humana. O relatório entrega o "se" e o
"quem"; o "quando" e o "quanto" não foram implementados.

## 3. O que NÃO está entregue

| item | por quê |
|---|---|
| mover cópias de fato (rebalanceamento ativo) | exige o corpo — hoje o Control Plane serve por digest e o nó busca; mover exige banda controlada, pausa/retomada e janela. Projeto próprio |
| host físico / operadora / rack como domínio | não estão no cadastro; derivar seria deduzir |
| storage classes (hot/cold, custo, durabilidade por classe) | o catálogo não tem classe de objeto; acrescentar exige decisão de produto (quem escolhe a classe de um objeto novo?) |
| erasure coding | muda o modelo de bytes+sha256 por objeto; hoje a base é réplica inteira |
| escolha por região na **tarefa** | já existe para despacho (`NetworkRegions`); no Storage só entrou na colocação |

## 4. Verificação

| verificação | resultado |
|---|---|
| `StoragePlacementServiceTests` | **13** (8 da fase 3 + 5 novos: duas réplicas na mesma região; usa a mesma quando não há outra; região não declarada ≠ declarada; não repete a região que já tem cópia; grafia não cria domínio) |
| `StorageBalanceReportTests` | **6** — desequilíbrio apontado; proporcional não aponta ninguém; `STALE` não conta; sem medida não afirma nada; nó sem medida fica com capacidade nula; sem nós não inventa base |
| `NetworkStorageBalanceTests` | **3** — rota exige admin; sem nó não inventa base; disco não medido chega nulo (não zero) |

Mutações conferidas:

| mutação | derruba |
|---|---|
| diversidade de região desligada (uma passada) | `naoDesignaDuasReplicasNaMesmaRegiao`, `naoRepeteARegiaoQueJaTemCopia` |
| sem a 2ª passada (só região distinta) | 6 testes de colocação |
| contar colocação `STALE` | `copiaNaoConfirmadaNaoContaNoRelatorio` |
| disco não medido virar `0` | 2 erros nos testes do relatório |
| usar `declared_disk_mb` em vez do medido | 3 testes do relatório |

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
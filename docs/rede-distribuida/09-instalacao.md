# 09 — Instalação do Node Runtime

O instalador transforma uma máquina comum em participante da rede sem que o
participante precise conhecer a arquitetura interna. Está em
[`deploy/node-runtime/`](../../deploy/node-runtime/):

| arquivo | papel |
|---|---|
| `docker-compose.yml` | o container do nó: `read_only`, `cap_drop: ALL`, `no-new-privileges`, limites de CPU/memória/PIDs, sem porta exposta e sem docker socket |
| `install.sh` | o fluxo de instalação em três passos (abaixo) |
| `node-doctor.sh` | diagnóstico PASS/WARN/FAIL — mesmo formato do `scripts/dev-doctor.sh` do backend |

## 1. Os três passos

```bash
# 1. Enroll com o token de provisionamento que o admin entregou por canal privado.
./install.sh --control-plane https://<control-plane> --token <token> \
             --region sa-east-1 --country BR

# 2. Depois de o admin gerar a API key na tela de administração, cole-a uma vez.
#    Ela fica em $NODE_HOME/api-key com permissão 600 e nunca aparece em log.
./install.sh --set-key

# 3. Sobe o container — mas só depois de provar o primeiro heartbeat com a chave.
./install.sh --start --control-plane https://<control-plane>
```

O `--start` valida a credencial contra o Control Plane **antes** de subir
qualquer container: subir um nó com chave revogada só criaria um loop de erro.

## 2. O que o instalador materializa das regras da rede

- **Token de uso único.** Rodar o enroll de novo com o mesmo token falha com
  `NODE_TOKEN_USED`; o install repetido com `node.json` existente não refaz a
  requisição.
- **API key não vem do enroll.** O enroll devolve `nodeId` e fica pendente; a
  chave é gerada pelo admin (`POST /admin/network/nodes/{id}/keys`) e o
  participante a cola uma vez com `--set-key`. Sem chave, o install para no
  passo 2 com a instrução do próximo passo.
- **Pull, não push.** O compose não publica porta nenhuma: o nó pergunta
  (`GET /network/tasks/next`) e o Control Plane nunca abre conexão para ele —
  participante atrás de NAT funciona.
- **Limite é contrato.** O container nasce com `mem_limit`/`cpus`/`pids_limit`:
  o nó não consegue consumir mais do que o participante declarou, mesmo se o
  processo dentro dele quiser.
- **Região como a doc documenta.** Quando `--region` é passado, o corpo vai
  aninhado em `location` (contrato §2), forma que o DTO exige e o teste
  `enrollComLocationDaDocGravaARegiao` fixa.

## 3. Diagnóstico

```bash
NODE_HOME=/var/lib/enois-node ./node-doctor.sh --control-plane https://<control-plane>
```

Checa dependências, identidade registrada, permissão da chave (600) e do
diretório (700), estado do container e conectividade com o Control Plane.
Sai 0 quando não há FAIL; WARN não impede a operação. Nunca imprime a API key.

## 4. Estado local

| caminho | conteúdo | permissão |
|---|---|---|
| `$NODE_HOME/node.json` | resposta do enroll (`nodeId`, estado) | 600 |
| `$NODE_HOME/api-key` | a API key do nó em claro — único lugar | 600 |
| volume `enois-node` | workspace do runtime no container | — |

`NODE_HOME` padrão é `/var/lib/enois-node`; sobrescreva por ambiente. O par
`nodeId` + chave é a identidade do nó: apagar os dois arquivos não "desregistra"
nada — a revogação é decisão do admin, e voltar após revogação exige cadastro
novo (identidade nova, chave nova).

## 5. O que ainda não está aqui

- **Executores por perfil** (fase 6 do plano): a imagem hoje prova
  conectividade, credencial e ciclo de vida; executar transcode/cache de verdade
  é o que falta para o nó receber tarefa e devolver resultado.
- **Atualização e rollback com verificação de assinatura OCI** (item próprio do
  plano): o compose pina tag, e a verificação de assinatura no pull entra com a
  publicação das imagens do runtime.
- **Backup de config:** a config é recuperável do Control Plane (`GET
  /network/nodes/me/config`, assinada); o que precisa de backup local é só o par
  de identidade, e a política de retenção dele acompanha a revogação.

# Assinatura da config dos nós — master e slave

Este pacote fecha o que falta para o nó sair de `signed:false` e pinar a chave
pública do Control Plane. O backend **já devolve** `publicKey` quando as duas
envs estão configuradas; o problema de campo é o nome/formato delas.

## O que já está pronto no backend

- `GET /network/nodes/me/config` devolve `publicKey` sempre que
  `rede.network.config.signing-public-key` está preenchida
  (`NetworkConfigService.forNode`, `NetworkConfigService.java:336-344`).
- Com o par válido, a resposta sai `signed:true`, `signature` Ed25519 e
  `publicKey` em X.509 DER base64.
- Os testes provam os dois estados:
  - `NetworkConfigServiceTests.comChaveConfiguradaAAssinaturaConfereComOPino`:
    `signed:true`, `publicKey` presente e assinatura verificável.
  - `NetworkConfigServiceTests.chaveDeAssinaturaInvalidaViraSemAssinaturaENaoMinta`:
    `signed:false`, mas `publicKey` continua presente quando só a privada está
    quebrada. Se a resposta tem `publicKey:null`, o problema é a env pública
    (ausente ou com nome errado), não só a privada.
- `configVersion` e `issuedAt` vêm do ciclo de vida do nó, não do relógio da
  requisição. Eles **não mudam** quando você configura a chave; a assinatura é
  recalculada a cada leitura. Não use esses dois como sinal de deploy.
- A doc de instalação do participante está em
  [`docs/rede-distribuida/09-instalacao.md`](../../docs/rede-distribuida/09-instalacao.md)
  (§1.1 e §1.2); este pacote é o lado operacional: gerar o par no Control
  Plane, provar a resposta assinada e pinar a chave no nó.

## Nomes e formato (a causa mais provável)

O mapeamento real está em `application.properties:733-734`:

```properties
rede.network.config.signing-key=${REDE_NETWORK_CONFIG_SIGNING_KEY:}
rede.network.config.signing-public-key=${REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY:}
```

As envs têm **underscore**:

```text
REDE_NETWORK_CONFIG_SIGNING_KEY
REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY
```

`REDE_NETWORK_CONFIG_SIGNINGKEY` e `..._SIGNINGPUBLICKEY` não são lidas — e,
como a propriedade fica vazia, não há warning.

O backend faz base64-decode e entrega os bytes direto em `PKCS8EncodedKeySpec`
e `X509EncodedKeySpec`. Isso é **DER**, não PEM. `base64 -w0 signing.pem`
decodifica para `-----BEGIN PRIVATE KEY-----...` e falha no parser (gerando o
warning de chave inválida). Os comandos corretos estão em
`docs/rede-distribuida/09-instalacao.md:90-97`:

```bash
openssl genpkey -algorithm ed25519 -out signing.pem
openssl pkey -in signing.pem -pubout -outform DER | base64 -w0
openssl pkcs8 -topk8 -nocrypt -in signing.pem -outform DER | base64 -w0
```

## Master (Control Plane / QA)

No host do backend:

```bash
sudo ./master/gerar-chave-assinatura.sh
# padrão: /etc/enois/config-signing
```

O script:

1. gera o par Ed25519;
2. grava `env` (600) com as duas variáveis já em DER base64;
3. grava `public-key.b64` e `public-key.sha256` (fingerprint) para compartilhar;
4. valida que a privada decodifica como PKCS#8 DER, que a pública decodifica
   como X.509 DER e que as duas são par.

Carregue as **duas** variáveis no serviço do backend e reinicie. O arquivo
`env` já tem o formato `KEY=valor`, mas os dois nomes contêm `=`, então `Podman
--env-file` e leitores que não aceitam `=` no nome precisam de ajuste. O
mecanismo depende do deploy:

```yaml
# docker compose: o env gerado tem o formato KEY=valor
services:
  backend:
    env_file:
      - /etc/enois/config-signing/env
```

```ini
# systemd: dentro da unidade do backend
# EnvironmentFile=/etc/enois/config-signing/env

# e recarregue, sem expor os valores:
sudo systemctl daemon-reload
sudo systemctl restart <servico-do-backend>
sudo systemctl show <servico-do-backend> -p Environment | sed 's/=.*/=<oculto>/g'
```

```bash
# Kubernetes: o secret nasce do mesmo arquivo, com metadados descartáveis para
# o nome não virar chave
kubectl -n <namespace> create secret generic rede-config-signing \
  --from-env-file=/etc/enois/config-signing/env \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n <namespace> rollout restart deploy/<backend>
kubectl -n <namespace> get secret rede-config-signing -o jsonpath='{.data}' \
  | tr ',' '\n' | sed 's/:.*/:<oculto>/'
```

Confira dentro do container/serviço **sem imprimir os valores**:

```bash
docker exec <container> sh -c '
  for v in REDE_NETWORK_CONFIG_SIGNING_KEY REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY; do
    if printenv "$v" >/dev/null; then echo "$v=presente"; else echo "$v=ausente"; fi
  done'
```

Prove que a resposta mudou, com a credencial de um nó:

```bash
./master/verificar-config-assinada.sh \
  --control-plane https://qa.exemplo \
  --node-id nd_... \
  --node-key-file /caminho/api-key \
  --expected-fingerprint "$(cat /etc/enois/config-signing/public-key.sha256)"
```

Saída esperada:

```text
formatVersion=4 configVersion=3 signed=true issuedAt=...
publicKey presente (44 bytes DER)
fingerprint=SHA256:...
fingerprint confere com o esperado
assinatura Ed25519 confere com a publicKey publicada
RESULTADO: OK — signed:true, publicKey presente e verificável
```

Compartilhe com o operador do nó apenas `public-key.b64` / `public-key.sha256`,
por um canal confiável. **A privada nunca sai do master.**

## Slave (nó)

Pré-requisitos: `NODE_HOME` com `node.json`, `api-key`, `control-plane`; o
`install.sh` do runtime; root/sudo (docker e chown do NODE_HOME).

Instalação como serviço systemd:

```bash
sudo ./slave/instalar-monitor.sh \
  --node-home /home/mel/enois-node/home \
  --install-sh /home/mel/enois-node/install.sh \
  --expected-fingerprint SHA256:...
```

O monitor, nos dois modos:

- lê a config a cada 60 s;
- exige `formatVersion:4`, `signed:true` e `publicKey` presente;
- calcula o SHA-256 do DER e **só pina se bater** com o fingerprint esperado;
- se já existe `pinning-key`, não sobrescreve;
- ao bater, roda `install.sh --start` uma vez e confere o pino gravado;
- para sozinho depois do sucesso (`exit 0`).

Acompanhe:

```bash
journalctl -u enois-config-signing-monitor.service -f
systemctl status enois-config-signing-monitor.service --no-pager
```

### Serviço longo ou timer

Os dois pinam exatamente do mesmo jeito; muda só quem agenda a checagem.

| | serviço longo (padrão) | `--timer` |
|---|---|---|
| unidades | `enois-config-signing-monitor.service` | `...-monitor.timer` + `...-monitor-once.service` |
| cadência | `sleep 60` dentro do processo | `OnUnitInactiveSec=60s` depois do fim da execução |
| fim do sucesso | a unidade fica `inactive` | o timer continua batendo e o oneshot sai 2 (`SuccessExitStatus`) |
| falha (3/5/6/…​) | log + unidade `inactive` | log + unidade `failed` no `systemctl --failed` |
| quando usar | nó que ainda vai receber a chave, um processo só | host onde serviço de longa duração é indesejado (política de CPU, contêiner, imagem enxuta) |

```bash
# trocar de modo: o instalador desabilita o outro lado
sudo ./slave/instalar-monitor.sh --timer \
  --node-home /home/mel/enois-node/home \
  --install-sh /home/mel/enois-node/install.sh \
  --expected-fingerprint SHA256:...

systemctl list-timers enois-config-signing-monitor.timer --no-pager
journalctl -u enois-config-signing-monitor-once.service -f
```

O instalador valida tudo (fingerprint, `NODE_HOME`, `install.sh`) **antes** de
gravar qualquer unidade: argumento errado não deixa serviço apontando para um
diretório que não existe. Para remover os dois modos de uma vez:

```bash
sudo ./slave/instalar-monitor.sh --uninstall
```

Sem fingerprint (modo detectar apenas):

```bash
sudo ./slave/instalar-monitor.sh --node-home ... --install-sh ...

# quando a chave aparecer, o log mostra o SHA-256 e o serviço para com exit 6.
# Confira com o master e reinstale com --expected-fingerprint.
```

Execução manual, sem instalar serviço:

```bash
sudo ./slave/monitorar-config-assinatura.sh \
  --node-home /home/mel/enois-node/home \
  --install-sh /home/mel/enois-node/install.sh \
  --expected-fingerprint SHA256:... \
  --dry-run --once
```

## Diagnóstico rápido

| Sintoma | Causa provável | Ação |
|---|---|---|
| `formatVersion:3` | backend anterior ao commit que mapeia as envs (#719) | deploy da `qa` atual; o formato atual é 4 |
| `signed:false` + `publicKey:null` | nenhuma env reconhecida | confira os nomes com underscore dentro do container |
| `signed:false` + `publicKey` presente | privada ausente ou inválida | log do backend deve ter o warning; use DER, não PEM; reinicie |
| `signed:true` + `publicKey:null` | pública ausente ou inválida | confira `REDE_NETWORK_CONFIG_SIGNING_PUBLIC_KEY` (X.509 DER) e reinicie |
| `signed:true` mas o nó não executa | pino ausente ou antigo | rode o monitor/`--start`; se o pino existe e é de outra chave, a troca é manual |
| monitor `exit 6` | config assinada, mas falta fingerprint | compare o SHA-256 com o master e reinstale |
| monitor `exit 3` | fingerprint divergente | **não force**; investigue rotação de chave ou master errado |
| monitor `exit 4` | tempo esgotado sem config assinada | corrija as envs no master; reinicie o monitor |

## Segurança

- A privada é segredo de assinatura: só no master, em arquivo 600, nunca em
  chat, issue, commit, log ou variável impressa.
- O `env` e o `signing.pem` ficam em `/etc/enois/config-signing` (700).
- O pino é raiz de confiança do nó: o monitor nunca o sobrescreve. Trocar o par
  exige remover `pinning-key` no nó — decisão manual do participante (TOFU).
- `--allow-tofu` existe para laboratório; em QA/produção use fingerprint.

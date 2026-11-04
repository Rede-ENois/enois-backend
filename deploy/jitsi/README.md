# Jitsi próprio do Énóis

Stack do [`docker-jitsi-meet`](https://github.com/jitsi/docker-jitsi-meet) para as chamadas de
áudio e vídeo do app, com **autenticação por token**: sem um JWT assinado pelo backend, ninguém
entra — nem como convidado.

O `docker-compose.yml` é o oficial da versão `stable-11248-3`, sem alteração de serviço. Toda a
configuração do Énóis está no `.env` (a partir do `.env.example`), que é o jeito suportado de
ajustar a stack.

## O que a stack sobe

| Serviço | Papel |
|---|---|
| `web` | A interface do Jitsi (o que o iframe do app carrega) |
| `prosody` | XMPP; é aqui que o token é conferido (`AUTH_TYPE=jwt`) |
| `jicofo` | Foco da conferência: cria e mantém a sala |
| `jvb` | Videobridge: por onde passa o áudio e o vídeo |

## Subir

```bash
cd deploy/jitsi
cp .env.example .env
# edite PUBLIC_URL, JVB_ADVERTISE_IPS e CONFIG
./gen-secrets.sh          # gera JWT_APP_SECRET, JICOFO_AUTH_PASSWORD e JVB_AUTH_PASSWORD
docker compose up -d
```

O `gen-secrets.sh` imprime o `JWT_APP_SECRET` uma única vez. Esse valor vai para o deploy do
backend como `REDE_CALLS_JITSI_JWT_SECRET`, junto com `REDE_CALLS_JITSI_URL` (o mesmo `PUBLIC_URL`)
e `REDE_CALLS_JITSI_JWT_AUDIENCE` (o mesmo `JWT_ACCEPTED_AUDIENCES`, `meet.jitsi` por padrão).
Sem URL e segredo, o app responde 503 `CALLS_NOT_CONFIGURED` e não abre sala.

## O que já vem fixado no `.env.example`

| Variável | Valor | Por quê |
|---|---|---|
| `ENABLE_AUTH` | `1` | Exige autenticação |
| `ENABLE_GUESTS` | `0` | Não existe entrada anônima |
| `AUTH_TYPE` | `jwt` | A credencial é o token do backend |
| `JWT_ALLOW_EMPTY` | `0` | Token vazio é recusado |
| `JWT_ACCEPTED_ISSUERS` | `rede` | Só aceita token do nosso backend |
| `JWT_ACCEPTED_AUDIENCES` | `meet.jitsi` | Token sem `aud` é recusado; tem que bater com `REDE_CALLS_JITSI_JWT_AUDIENCE` |
| `ENABLE_RECORDING` / `ENABLE_TRANSCRIPTIONS` | `0` | Nada de gravação por padrão |
| `ENABLE_LOBBY` / `ENABLE_PREJOIN_PAGE` / `ENABLE_WELCOME_PAGE` | `0` | O painel do app já é a entrada |
| `DISABLE_DEEP_LINKING` | `1` | Nada de mandar a pessoa para fora do app |
| `JITSI_IMAGE_VERSION` | `stable-11248-3` | Versão fixa; `unstable` muda sozinho |

## Rede e TLS

O `web` publica `HTTP_PORT`/`HTTPS_PORT` no host. Em produção, ponha um proxy reverso na frente
com o certificado do domínio e aponte `PUBLIC_URL` para ele; o proxy precisa repassar o upgrade de
WebSocket (o Jitsi usa XMPP sobre WebSocket).

A mídia **não** passa pelo proxy: o `jvb` publica `JVB_PORT` (UDP 10000) direto. Libere essa porta
no firewall e preencha `JVB_ADVERTISE_IPS` com o IP público do servidor — sem isso os navegadores
não conseguem trocar áudio e vídeo quando o servidor está atrás de NAT.

## Operação

```bash
docker compose logs -f prosody   # entradas aceitas/recusadas
docker compose logs -f jvb       # mídia e conferências
docker compose ps
```

- **Trocar o segredo**: gere outro, atualize `JWT_APP_SECRET` no `.env` e
  `REDE_CALLS_JITSI_JWT_SECRET` no backend, e reinicie os dois. As chamadas em andamento caem; as
  novas usam o novo segredo.
- **Atualizar a versão**: troque `JITSI_IMAGE_VERSION` por uma tag `stable-*` e rode
  `docker compose pull && docker compose up -d`. Não use `unstable` em produção.
- **Backup**: o que importa é o `.env` (segredos) e o diretório `CONFIG`. Nada de dado de usuário
  fica na stack — a chamada não é gravada.

## Verificação

```bash
# A interface responde
curl -sI "$PUBLIC_URL" | head -1

# Sem token, a sala não abre (o Jitsi devolve a tela de erro de autenticação)
curl -s "$PUBLIC_URL/teste-sem-token" | grep -qi "auth\|token" && echo "protegido"
```

A prova de mídia de verdade (dois navegadores, pacotes de áudio e vídeo nos dois sentidos) está
descrita em [`docs/CHAMADAS.md`](../../docs/CHAMADAS.md).

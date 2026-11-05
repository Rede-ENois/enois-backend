# Salas de chamada e de mentoria — operadas pela Epapo

As chamadas e as salas de mentoria do Énóis usam a **Epapo**, a videoconferência própria da rede
([`Rede-ENois/epapo`](https://github.com/Rede-ENois/epapo)), que é um fork do Jitsi Meet com a
integração do Énóis já embutida. A stack que era instalada daqui (`docker-compose.yml` +
`gen-secrets.sh`) não é mais o caminho de instalação e **não deve ser usada**: ela não tem o
*auth gateway* que pede a autorização ao Énóis, então salas subidas por ela não seguem a regra de
acesso das chamadas nem das mentorias.

## Instalação

A instalação, as variáveis e o contrato estão no repositório da Epapo:

- `deploy/README.md` — subir a stack (web, auth-gateway, edge, prosody, jicofo, jvb).
- `deploy/INTEGRACAO-ENOIS.md` — o contrato `POST /auth/token` ↔
  `POST /calls/{callId}/gateway/entry/{userId}` e `POST /mentoring/{sessionId}/gateway/entry/{userId}`,
  mais as regras de segurança.

## O que este backend precisa

| Variável | De onde vem |
|---|---|
| `REDE_CALLS_EPAPO_URL` (legado: `REDE_CALLS_JITSI_URL`) | `PUBLIC_URL` da Epapo |
| `REDE_CALLS_EPAPO_GATEWAY_URL` | o gateway na rede privada, `http://auth-gateway:8090` |
| `REDE_CALLS_EPAPO_INTERNAL_TOKEN` | `EPAPO_INTERNAL_TOKEN` da Epapo |
| `REDE_CALLS_GATEWAY_AUTHORIZE_TOKEN` | `ENOIS_AUTHORIZE_SECRET` da Epapo |
| `REDE_MENTORING_ROOM_BASE_URL` (opcional) | host das salas de mentoria; vazio usa o host das chamadas |

O `JWT_APP_SECRET` não fica aqui: quem assina o JWT é o gateway da Epapo. Detalhes em
[`docs/CHAMADAS.md`](../../docs/CHAMADAS.md).

Os arquivos `docker-compose.yml` e `gen-secrets.sh` continuam no repositório só como registro
histórico da primeira versão; não são mantidos nem testados.

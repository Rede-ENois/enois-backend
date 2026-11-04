# Acesso ao host pelo Actions

Workflow [`acesso-host.yml`](../.github/workflows/acesso-host.yml): roda um comando no host,
disparado à mão pelo Actions — o mesmo caminho dos workflows de diagnóstico, sem SSH e sem chave.
A entrada é a API do Docker do Portainer; as credenciais vêm dos secrets que os workflows de
diagnóstico já usam.

Dois alvos, escolhidos no próprio formulário:

| `alvo` | host | endpoint do Portainer |
| --- | --- | --- |
| `qa` (padrão) | `154.12.230.97` (`docker-prod4`) | 8 |
| `prod` | `enois-node2` | 11 |

## Como usar

1. **Actions → 🖥️ Acesso host (shell) → Run workflow** (branch `main`).
2. Escolha o `alvo` (`qa` ou `prod`), escreva o `comando` (aceita várias linhas) e deixe `sigiloso`
   como está, a menos que o comando leia senha, token ou dado pessoal:

   ```
   uptime
   df -h
   docker ps --format '{{.Names}}\t{{.Status}}'
   ```

3. O job fica **esperando aprovação**. Qualquer owner da organização que não seja quem disparou
   aprova — o aviso aparece no próprio run, no botão "Review deployments". Sem aprovação, o job não
   roda.
4. Depois da aprovação, o resultado aparece no log, entre `--- comando ---` e `--- fim ---`, seguido
   do `codigo_de_saida`. Comando que falha deixa o run vermelho.

O comando roda **como root no sistema do host** (mesmo `/`, mesmo `/proc`, mesmas ferramentas),
então `docker`, `systemctl`, `ps`, `du` e os binários do host valem.

## Quem pode disparar

Qualquer pessoa com escrita no repositório **pode disparar**, mas nada roda sem aprovação:

- o job usa um ambiente protegido por alvo (`acesso-host-qa`, `acesso-host-prod`);
- os aprovadores são owners da organização (o GitHub aceita até seis por ambiente; estão listados os
  seis com mais contribuições), e **quem disparou não aprova o próprio run**;
- administrador de repositório não fura a regra;
- só aceita execução a partir da `main`.

Incluir ou tirar um aprovador é mudança nos ambientes do repositório (Settings → Environments),
não no código.

## O que aparece no log

Por padrão, **tudo**: o comando e a saída vão para o log do run, que é público. Para caso em que a
saída tem senha, token ou dado pessoal, ligue `sigiloso`: aí o log mostra apenas quantas linhas e
bytes vieram, nunca o conteúdo e nunca o texto do comando.

O log do passo também pode ser lido por quem tem acesso ao repositório através do job
(`gh run view --log`), então `sigiloso` é o certo para `.env`, credencial e dado de usuário. Para
investigar sistema (disco, memória, processos, containers), o padrão serve.

## Por que não roda no runner self-hosted

O `docker-prod4` tem, no próprio disco, as credenciais do Portainer e os secrets de deploy. Um job
com comando livre ali entrega essas credenciais para qualquer comando. Este workflow roda em
`ubuntu-latest` e recusa explicitamente qualquer runner que não seja hospedado pelo GitHub.

## O que ele faz por baixo

- Cria um container efêmero `acesso-host-<run>` com o sistema do host montado em `/host` (mais
  `/proc` e `/sys`), roda o comando por `chroot /host` e **remove o container no fim, sempre** —
  inclusive quando o comando falha, sai por timeout ou volta com código diferente de zero.
- Esse container tem o poder de root no host: é o que o objetivo pede (ler disco, processo e
  container), e é por isso que a porta de entrada é o ambiente com aprovação.
- Se o pull do `alpine` falhar, usa a imagem de um container que já está rodando no host (não
  depende de nome de container).
- Um container por execução (nome com o `run_id`). Dois runs ao mesmo tempo não colidem no nome —
  mas, como cada um é root no mesmo host, dois comandos que escrevem no mesmo lugar continuam se
  atrapalhando. Não conte com isolamento entre execuções.

## Limites

- 20 minutos por execução.
- Sem TTY de verdade: prefira comandos que terminam sozinhos (`journalctl --no-pager`, `tail -n`,
  `docker logs -n`). `top` e `vim` não funcionam; `ps`, `df`, `du`, `free` funcionam.
- Comando que reinicia serviços de rede pode cortar a própria saída; rode em dois passos (descobrir,
  depois agir).
- **Produção é produção.** O alvo `prod` (`enois-node2`) é o host que serve o site. Prefira leitura
  (`docker ps`, `df`, `journalctl`, `docker logs`) e use comando que escreve só quando for isso mesmo
  que se quer.

## Auditoria

- Cada execução é um run em `Actions`, com quem disparou, quem aprovou, horário e código de saída.
- Histórico: `https://github.com/Rede-ENois/enois-backend/actions/workflows/acesso-host.yml`

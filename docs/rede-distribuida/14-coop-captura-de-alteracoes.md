# Énóis Global Cooperative — captura de alterações: prontidão medida

Continua [`11-global-coop-fase-0.md`](11-global-coop-fase-0.md), que deixou a captura por binlog como
decisão de infraestrutura do mantenedor (doc §4, item 2 da ordem recomendada).

## 1. O que está entregue

`GET /admin/network/nodes/change-capture` (admin) mede, no banco em que a aplicação roda:

| o que | por quê |
|---|---|
| `@@log_bin` | desligado, nenhuma alteração é gravada para captura |
| `@@binlog_format` | `STATEMENT` guarda o **comando**, não a linha: duas réplicas divergem. Captura precisa de `ROW` |
| `@@binlog_row_image` | documento do operador: `FULL` é o que permite reconstruir a linha inteira |
| `@@max_binlog_size` | quanto o servidor rola o arquivo — muda o ritmo de rotação |
| `@@version` | o mesmo relatório em versões diferentes não descreve o mesmo servidor |

Os cinco vêm em **uma consulta**: três consultas separadas descreveriam dois servidores se houvesse
reinício entre elas.

**"Não sei" é uma resposta.** `ready` é `null` quando não foi possível consultar (H2 da suíte,
usuário sem permissão, variável ausente) — e é diferente de `ready: false`, que é "medi e não está
pronto". Um serviço que tratasse a falha como "desligado" marcaria todo ambiente de teste como
quebrado; um que tratasse como "ligado" mentiria em produção.

## 2. O que NÃO está entregue (e por quê)

| item | por que |
|---|---|
| **ler o binlog** | exige `log_bin` ligado, `REPLICATION SLAVE`/`CLIENT`, usuário dedicado e posição persistente — decisões de infraestrutura, não efeito colateral de código |
| **checkpoint durável e retomada** | dependem do leitor existir; o change feed escrito pela aplicação (V312) já tem cursor próprio, e é o mecanismo em uso |
| **backpressure** | item do §4 que só faz sentido com um consumidor de binlog |
| **CDC por outra via** | seria um segundo mecanismo de sincronização — exatamente o que o brief proíbe (§ reutilizar, nunca segundo sistema) |

`rede.network.change-capture.required` (padrão `false`) permite que a instalação **declare** a
decisão tomada: quando `true`, a prontidão medida passa a ser erro de configuração se não estiver
pronta.

## 3. Verificação

`ChangeCaptureReadinessServiceTests` (5): desligado não é pronto; `STATEMENT` não serve; `ROW` é
pronto; servidor que não responde é **desconhecido, não desligado**; as variáveis vêm numa consulta
só. Mutações conferidas: tratar falha como "desligado" derruba o teste do desconhecido; aceitar
`STATEMENT` derruba o teste do formato.

# CLAUDE.md

@AGENTS.md

O `AGENTS.md` (importado acima) é o contrato operacional de todo agente de IA neste repositório. As
regras valem por inteiro; a de baixo é repetida porque é a mais fácil de pular:

- **Regra do escoteiro: toda falha é corrigida quando encontrada.** Teste quebrado ou instável,
  build quebrado, erro de lint ou de tipo, bug ou falha de segurança que você encontrar é corrigido
  na mesma tarefa, mesmo que seja anterior à sua mudança. Se o conserto estiver realmente fora de
  alcance, registre (issue ou item do backlog) e diga na PR — nunca deixe em silêncio.

<!-- engineering-learn:live -->
- Controle de segurança em caminho quente (filtro, limitador, contador de tentativas): nada de consulta ao banco por requisição sem cache e sem cota por IP, repositório JPA em filtro só via `ObjectProvider`, e limite de tentativas é reivindicado atomicamente (`UPDATE ... where attempts < N`) antes de conferir o código — nunca ler, conferir e só depois incrementar.
- Migração que neutraliza dado perigoso usa denylist explícita do que é perigoso (ex.: `^(javascript|vbscript|data|file):` depois de tirar `[\x00-\x20]` do início), nunca allowlist com exceções em regex — a exceção (`host:port`) virou brecha.
- Docker em tarefa de agente: remova só o container que você criou, pelo nome (`docker rm -f <nome>`); nunca `docker container prune`, `system prune` ou remoção em massa — apaga containers de outros projetos da máquina.
- Item que ocupa um slot do perfil (estilo de nome, tema, moldura): antes de gravar, confira como o front/DS desenha aquele slot e zere os campos vizinhos que mudam o desenho (fonte e cor do nome sobrescrevem o tema sazonal) — o teste afirma o estado completo do slot, não só o id.
<!-- /engineering-learn:live -->

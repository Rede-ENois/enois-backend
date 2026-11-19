# Autoridade, segredos e estado legado

Os estados abaixo são inventariados pelo nome e pelo dono. Dados de
participantes, credenciais, caminhos reais, quantidades em fila e registros de
produção ficam no inventário restrito dos operadores para o ensaio J8.

## Estado durável e transição

| Estado atual e fonte | Propriedade proposta e evidência de passagem |
| --- | --- |
| Inscrições, consentimento, aprovações, nós, papéis, hash de chave, regiões, capacidade, tráfego e saúde (V305, V311, V313, V318, V321, V324/V325, V341–V350) | Histórico e consentimento permanecem no legado com retenção e acesso restritos. CP emite nova aprovação por papel e versão. Validar revogação e limites em paralelo controlado por capacidade. |
| Configuração assinada, modo, geração, termo, chaves de nó e certificados (V305, V307, V313, V317, V358 e `deploy/node-runtime`) | Nova admissão, lease e identidade pertencem ao CP. Preservar evidência histórica do termo, sem conversão em época de escrita. Raízes, certificados e chaves legados têm revogação/expiração próprias. |
| Tarefas e tentativas, mídia delegada, alvos/regiões (V306, V309, V314, V316) | Drenar ou cercar tentativas e comparar as pendências, IDs, resultados e recibos antes de autorizar o novo dono. Migração por capacidade com cancelamento explícito ou reenfileiramento verificável. |
| Sessões, rotação e escopo, tokens OAuth/2FA e credenciais (V1, V16, V22, V28, V253, V368 e migrações de fatores) | Identidade na camada de dados. Retenção e expiração acompanham os registros. Recuperar hash/rotação/fatores ou aprovar invalidação visível. Réplica REST recebe somente contexto suficiente para autorizar a solicitação. |
| Idempotência, outbox, recibos, auditoria, retenção (V1, V171, V252, V261, V315) | Preservar chaves já aceitas, hashes, status e respostas reproduzíveis, pendências e recibos. Comparar efeito pendente/confirmado antes de retomar trabalho. |
| Projeções públicas, versões de conteúdo e cursor dos participantes (V307/V308, V310/V312) | Dados preservam versão/visibilidade e paginação. Projetores retomam de um cursor compatível ou reconstroem snapshot limitado pela autorização atual. |
| Sinais de instância e leitores | `InstanceSignalStore` fica na camada de dados. Cada réplica tem posição independente e mantém os próprios emissores e caches. Uma leitura não consome sinais para as outras. Guardar retenção, atraso, descarte de leitores e recuperação por reset. |
| Objetos, placements, destino verificado, políticas e versões (V319/V320, V323, V348, V352, V354, V357, V365–V367) | Inventariar IDs, versão corrente, política de retenção, tombstones, tamanho, digest, cópias e uploads em andamento. Validar lastro físico, última cópia válida e restauração de versão antes de mudar autoridade. |
| Bytes na origem, armazenamento local de nó, destinos S3/ImageKit e cópias de contingência (V364, `rede.storage.node-body-root`, `deploy/node-runtime`) | Inventariar roots, buckets e volumes no registro restrito, com dono, classe, retenção e prova de restauração. Preservar bytes depois de transferir o catálogo. A cópia em disco sob o CP legado passa para armazenamento confiável quando validada. |

## Participantes

| Perfil do `NodeProfile` | Destino candidato |
| --- | --- |
| `NODE_API`, `NODE_LOCAL` | Avaliar cada autorização de origem. Admissão como `backend-replica` exige operador confiável, grant novo, aplicação atestada e réplica sem acesso direto a SQL. `NODE_LOCAL` indica preferência legada, sem permissão adicional. |
| `NODE_WORKER`, `NODE_MEDIA` | Somente tarefas versionadas com capacidade, tentativa e escopo explícitos. Componentes de M3 usados para prova genérica. Participação externa depende de M4. |
| `NODE_EDGE`, `NODE_READ_REPLICA` | Cache/entrega/projeções públicas têm contrato próprio. Conservar visibilidade, cursors e regras de invalidação durante transição. Avaliar continuidade separada em J8. |
| `NODE_STORAGE`, `NODE_STORAGE_STANDARD`, `NODE_STORAGE_MEDIA`, `NODE_STORAGE_ARCHIVE`, `NODE_STORAGE_EDGE`, `NODE_STORAGE_REPLICA`, `NODE_STORAGE_HOME` | Preservar região, cotas, banda, durabilidade, versões e política de colocação. Validar confiança e domínio de falha com operador antes de novo grant. O perfil HOME conserva capacidade declarada, sujeita à política real de durabilidade. |
| `NODE_EMERGENCY` | Inventariar responsabilidades em operação e cópias disponíveis. Promoção de escritor depende de restauração testada e fencing de armazenamento. |

## Segredos e estado de processo

| Classe | Dono atual | Dono proposto |
| --- | --- | --- |
| Credenciais MariaDB, pool, Flyway e chaves de proteção de credenciais | Aplicação Java e configuração de deploy | Serviço de dados confiável por ambiente. A API REST usa o cliente autorizado de dados. |
| Chaves de ingresso, emissão de grants, enrolamento e artefato | CP, gateway e agente na plataforma. No legado, Java emite as chaves de nó e assina a configuração | CP, gateway e agente segundo o escopo de cada credencial. Agente mantém tokens do túnel. Handoff ao Java com contexto privado e prazo validado em J2. |
| Segredos de OAuth, identidade, Turnstile, chamada, webhooks, e-mail, push e GitHub | Clientes e handlers de integração da aplicação Java | Dono da integração ou executor confiável, com escopo por ambiente/operação. Recepção de webhook, autenticação do provedor e efeito durável exigem revisão juntos. |
| Credenciais de transferência de objetos, certificados de storage e chaves de anexos privados | Aplicação Java, serviços de mídia/storage e participantes de storage | Storage/mídia confiável, com autorizações limitadas à ação e ao objeto. |
| Cache Caffeine, emissores SSE e presença de visualização | Cada processo Java. O banco transporta sinais de instância | Cópias locais por réplica. Use sinais, TTL e reconexão como já prevê o contrato de broadcast. Sob configuração compartilhada, não gravar spool em caminhos concorrentes. |
| Limites por IP, usuário ou dispositivo, deduplicação, modo pânico, bloqueios e risco | Filtros e serviços da aplicação com memória local e estado SQL conforme a regra | Validar política de ingressos e dados compartilhados. Contadores locais multiplicam o orçamento com o número de réplicas. O modo pânico mantém cópia local a partir de estado central. |
| Arquivos temporários, logs e spool de observabilidade | Processo Java e filesystem de cada instalação | Processo local com diretório independente, limites e retenção. Ferramentas de mídia e cópia exigem orçamento de CPU, memória, espaço, timeout e limpeza. `MediaFfmpegRunner` executa na mídia confiável. `CommandLineStandbyDumpSource` requer autoridade de backup dos dados. |
| Ações regulares e inicialização | Agendador e contexto Spring da aplicação Java | Conferir [agendamentos](agendamentos.csv), [ciclo de vida](ciclo-de-vida.csv) e [recursos locais](recursos-locais.csv) por assembly em J1/J5. A presença em scan Spring pode instalar scheduler, listener ou runner mesmo com o despachante legado desligado. |

As fontes do modelo legado estão nos [documentos de rede](../README.md) e nas
migrações de [banco](../../DATABASE.md). Antes do ensaio, o operador preenche
o snapshot concreto e a sequência de troca por capacidade, incluindo fence,
drain e reversão. O novo dono só recebe tráfego ou trabalho quando houver
evidência de que a autoridade anterior foi interrompida para a capacidade.

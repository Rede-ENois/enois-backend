-- Tabela de candidaturas do processo seletivo: a listagem pagina por (applied_at, id) em ordem
-- decrescente e filtra por estado, dentro da vaga. Sem o indice composto, cada pagina ordenava a
-- vaga inteira em memoria — e vaga com milhares de candidaturas ordena a cada rolagem.
--
-- O id entra como desempate: duas candidaturas podem cair no mesmo segundo (a coluna guarda
-- segundos), e paginar so pelo instante estrito pularia ou repetiria linha empatada.
--
-- Aditivo e idempotente: nenhum dado muda, so o custo de leitura.
create index if not exists idx_job_applications_job_applied
  on job_applications (job_id, applied_at, id);
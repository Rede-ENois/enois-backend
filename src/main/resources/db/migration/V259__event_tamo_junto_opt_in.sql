-- O "Tamo junto" do encontro vira escolha de quem cria o evento, e nao algo sempre presente:
-- quem organiza um encontro nao quer, necessariamente, uma sala de chamada no card dele.
--
-- Aditiva e idempotente: a coluna nasce falsa em todo evento que existe, entao nada muda para os
-- ja criados — quem quiser a sala de novo liga no formulario (a edicao aceita a mesma caixinha).
alter table events
  add column if not exists tamo_junto boolean not null default false;

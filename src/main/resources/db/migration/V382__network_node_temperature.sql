-- Énóis Distributed Network: temperatura do nó medida pelo runtime (brief §8.2, "quando disponível").
--
-- POR QUE EXISTE: o §8.2 pede a temperatura da máquina de cada nó. O Control Plane não abre conexão
-- para dentro do participante (brief §17), então quem lê é o runtime — de
-- `/sys/class/hwmon/hwmon*/temp*_input` e das zonas térmicas do kernel — e manda no heartbeat que
-- já existe. Sem coluna, a medida não teria onde pousar.
--
-- O QUE É A MEDIDA: a MAIOR leitura válida entre as fontes da máquina, em graus Celsius. Uma máquina
-- tem CPU, GPU, NVMe e placa com temperaturas diferentes; a que decide risco de limiar é a maior, e
-- a média esconderia o componente que está cozinhando. "Quando disponível" é levado a sério: máquina
-- virtual, container sem o `/sys` do host e sessão sem permissão de leitura não têm o sensor — e aí a
-- coluna fica NULA. Nulo é "não medido"; 0 seria uma temperatura que ninguém leu, e viraria gráfico
-- com um sensor que não existe.
--
-- A série curta (`network_node_samples`) ganha a mesma coluna para o quadro ao vivo.
--
-- Aditiva e idempotente, como as demais deste módulo.

alter table network_nodes
  add column if not exists observed_temperature_c double null after observed_disk_latency_ms;

alter table network_node_samples
  add column if not exists temperature_c double null after disk_latency_ms;
# Desenvolvimento local — backend + frontend

Guia do ambiente **completo**: API, interface, e-mail de confirmação e upload de mídia, sem
depender de bucket, ImageKit ou credencial de produção. Verificado em **07/11/2026** com JDK 25,
Node 24 e H2.

> O backend e o frontend são repositórios separados, mas o dev local é um fluxo só: o Vite serve a
> interface em `http://localhost:5173` e encaminha `/api/...` para o Spring Boot em
> `http://localhost:8081`.

## 1. Requisitos

| Ferramenta | Versão | Observação |
| --- | --- | --- |
| JDK | **25** | `java -version`; o build usa Temurin 25 |
| Maven | — | use o wrapper `./mvnw` |
| Node.js | **>= 22.12** | `node -v` |
| npm | — | vem com o Node |
| Docker | opcional | só para o Mailpit (e-mail local) |
| `NODE_AUTH_TOKEN` | — | PAT classic com `read:packages`; o design system vem do GitHub Packages |

No backend, o diagnóstico rápido é:

```bash
bash scripts/dev-doctor.sh
```

Corrija os `FAIL` antes de continuar; `WARN` é limitação, não bloqueio.

## 2. Mapa de portas (leia antes de subir qualquer coisa)

| Porta | Quem usa | Pode parar? |
| --- | --- | --- |
| **8080** | `coolify-proxy` (Traefik) | **NÃO.** Derruba os sites do Coolify. |
| **8081** | backend local (Spring Boot) | sim, é o seu processo |
| **5173** | frontend local (Vite) | sim |
| **8025** | Mailpit (interface web) | sim |
| **1025** | Mailpit (SMTP) | sim |

O Vite está fixado em **5173** (`server.port` + `strictPort` no `vite.config.ts`). Se você já tinha
um `npm run dev` aberto antes desta mudança, reinicie-o para pegar a porta. Não deixe ele cair em
8081: era exatamente isso que fazia o front “sumir” e o `/api` responder 404/CORS. **5173 é a única
origem liberada no CORS e nos redirects OAuth do perfil `dev`.**

## 3. E-mail local (Mailpit)

O cadastro só termina depois de confirmar o e-mail. O backend não registra o link em log; o
caminho local é capturar a mensagem no Mailpit.

```bash
docker run -d --name rede-mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit:v1.32.0

# se o container já existir:
docker start rede-mailpit
```

Interface: **http://localhost:8025**. Sem SMTP configurado, o cadastro responde
`emailSent: false` e nenhuma mensagem chega.

## 4. Backend

### 4.1 Subir pelo Maven (dia a dia)

```bash
cd enois-backend

SPRING_PROFILES_ACTIVE=dev \
REDE_SECURITY_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
FRONTEND_URL=http://localhost:5173 \
MAIL_HOST=localhost MAIL_PORT=1025 \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=false \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=false \
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=false \
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=8081 --server.servlet.context-path=/api"
```

Por que cada item:

- **`SPRING_PROFILES_ACTIVE=dev`** — o `spring-boot:run` já usa `dev` pelo `pom.xml`; a variável é
  **obrigatória** se você rodar o `.jar`. Não existe perfil padrão: o app recusa subir sem um.
- **`--server.servlet.context-path=/api`** — o proxy do Vite encaminha `/api/...` **com o prefixo**
  (em produção é o gateway que remove o `/api`). Sem isso o front recebe **404 em tudo**.
- **`REDE_SECURITY_ENCRYPTION_KEY`** — o boot pode passar sem ela por causa do `lazy-initialization`,
  mas o desafio de cadastro responde **500**. É obrigatória para criar conta no dev.
- **`FRONTEND_URL`** — monta o link do e-mail de confirmação (`/auth/verify?token=...`). O default
  do perfil `dev` é vazio; sem isso o link chega quebrado.
- **`MAIL_*`** — aponta o envio para o Mailpit (porta 1025, sem TLS/auth).

O primeiro boot roda as **220 migrações Flyway** e leva ~40–60s. O banco é **H2 em memória**:
reiniciar o backend apaga contas, posts e assets; os arquivos de mídia em disco permanecem.

### 4.2 Conferir que subiu

Em outro terminal:

```bash
curl -s http://localhost:8081/api/version
curl -s http://localhost:8081/api/actuator/health
```

- `/api/version` deve responder **200**.
- `/version` (sem `/api`) responde **404** — é o sintoma clássico de context-path faltando.
- Se o health vier **503**, o corpo mostra qual componente está `DOWN` (SMTP fora do ar é o mais
  comum); `/api/version` continua sendo o sinal de que o processo subiu.

Para conferir o modo de mídia, suba com `--rede.version.details-token=segredo-local` e consulte:

```bash
curl -s -H "X-Version-Details: segredo-local" http://localhost:8081/api/version
# ... "midia":{"storage":"local","uploads":"s3-assinado"} ...
```

### 4.3 Rodar o jar empacotado

```bash
./mvnw -DskipTests package

SPRING_PROFILES_ACTIVE=dev \
REDE_SECURITY_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
FRONTEND_URL=http://localhost:5173 \
java -jar target/rede-0.0.1-SNAPSHOT.jar \
  --server.port=8081 --server.servlet.context-path=/api
```

## 5. Frontend

```bash
cd enois-frontend

cp .env.example .env    # se ainda não existe

export NODE_AUTH_TOKEN=ghp_seu_token_com_read_packages
npm install
npm run dev             # http://localhost:5173
```

O `.env` local (é ignorado pelo git; **não commite**) precisa de:

```env
# Destino do proxy e dos loaders SSR — SEM /api no final
BACKEND_TARGET=http://localhost:8081

# Relativo: a sessão fica em cookies de localhost e passa pelo proxy do Vite
VITE_API_BACKEND=/api

# Origem que o SSR declara no X-Client-Origin (a mesma liberada no CORS do dev)
VITE_SITE_URL=http://localhost:5173
```

Armadilhas que já custaram tempo:

- `BACKEND_TARGET` **com** `/api` no final vira `/api/api/...` → **404**.
- O SSR (ex.: perfil em `/perfil/$username`) roda no servidor, onde não há proxy: ele usa o mesmo
  `BACKEND_TARGET` direto.
- Se `npm run dev` disser que **5173 está em uso**, há outro Vite aberto: pare o outro. Não troque
  para 5174+ (não está no CORS) e não deixe cair em 8081.
- Acesse por `http://localhost:5173`, **não pelo IP** da máquina (origem/cookies).
- `npm install` com **401 Unauthorized** significa `NODE_AUTH_TOKEN` ausente/sem `read:packages`.

## 6. Primeiro acesso

1. Abra **http://localhost:5173** e crie a conta.
2. O cadastro usa um **desafio de soma** e exige **pelo menos 1,2s** entre abrir o desafio e enviar
   (`SIGNUP_CAPTCHA_TOO_FAST`). Nome contendo `probe` é recusado pelo guard de cadastros.
3. O e-mail de confirmação cai no **Mailpit** (http://localhost:8025). Abra o link e a sessão já é
   criada.
4. Reiniciar o backend zera o H2: a conta some (esperado). Crie outra.

## 7. Upload de mídia no dev

O perfil `dev` usa **`rede.media.storage=local`**: os objetos vão para disco e são servidos pelo
**mesmo** `PUT/GET /api/storage/{chave}` de produção. Ou seja: upload funciona **sem bucket e sem
ImageKit**, e a sanitização de metadados e a moderação continuam no caminho — não existe bypass por
ser “local”.

- Diretório padrão: `/tmp/rede-media-dev` (arquivos `uploads/{mediaId}/file.ext`).
- Trocar o destino: `REDE_MEDIA_STORAGE=s3` (ou `imagekit`) no ambiente do backend.
- Trocar o diretório: `REDE_MEDIA_LOCAL_DIR=/algum/diretorio`.
- Limpar: pare o backend e `rm -rf /tmp/rede-media-dev`.
- O `cdnUrl` é relativo (`/api/storage/...`), então o `<img>` carrega pelo proxy do Vite — sem host
  fixo e sem CSP extra.
- Detalhes de produção/migração: [`MIDIA-S3.md`](MIDIA-S3.md).

Checklist rápido: publique um post com imagem → a imagem aparece no feed. Reinicie o backend → a
conta some (H2), mas o arquivo continua no diretório.

## 8. Problemas comuns

| Sintoma | Causa provável | Correção |
| --- | --- | --- |
| `503 UPLOADS_DISABLED` | backend antigo (antes do storage local) ou `REDE_MEDIA_STORAGE=imagekit` | confira `/api/version` com `X-Version-Details`; encerre o processo velho da 8081 e suba o novo |
| **404 em tudo** no front | backend sem `--server.servlet.context-path=/api` | suba com o context-path |
| CORS / “origem recusada” | front em 5174+ ou acessado por IP | use `http://localhost:5173` |
| Cadastro **500** | sem `REDE_SECURITY_ENCRYPTION_KEY` | gere e exporte a chave |
| `emailSent:false` / e-mail não chega | Mailpit parado ou `MAIL_*` ausente | suba o Mailpit e configure as variáveis |
| `SIGNUP_CAPTCHA_TOO_FAST` | envio em menos de 1,2s | espere e envie de novo |
| Vite abre em 8081 | config antiga (antes do `strictPort`) e 8080 ocupada | atualize o repo e reinicie `npm run dev` |
| **8081 em uso** | backend antigo/`mvnw` preso | veja a seção abaixo |
| `npm install` 401 | falta `NODE_AUTH_TOKEN` com `read:packages` | exporte o token e rode de novo |
| Conta/posts sumiram | H2 em memória reiniciou | esperado no `dev` |
| Imagem quebrada no feed | arquivo removido de `/tmp/rede-media-dev` (ex.: limpeza do `/tmp`) | envie a imagem de novo |

### Processo preso na 8081

```bash
ss -ltnp | grep ':8081'          # mostra o PID
ps -o pid,lstart,cmd -p <PID>    # confirme que é um backend local
kill <PID>                       # se não sair: kill -9 <PID>
ss -ltn | grep ':8080'           # confirme que o Traefik/Coolify segue de pé
```

**Nunca** use `pkill java` nem mate nada na **8080**.

## 9. Testes e verificação

Backend:

```bash
./mvnw -Dtest='LocalMediaStorageTests,MediaUploadsDisabledTests' test
./mvnw test          # suíte completa
```

Frontend:

```bash
npm test
npm run lint
npx vitest run src/lib/api/media.test.ts
```

> O `npm run typecheck` ainda acusa erros antigos fora da mídia (exports do design system e a
> tipagem de `devProxy` no `vite.config.ts`). Não são causados por este fluxo; se mexer neles,
> corrija na mesma entrega.

## 10. Limpeza

```bash
# pare os processos com Ctrl+C; se algo ficou preso:
ss -ltnp | grep -E ':(8081|5173)'   # mate só os PIDs locais

rm -rf /tmp/rede-media-dev          # mídia local
docker rm -f rede-mailpit           # e-mails de teste (opcional)
```

O H2 não tem arquivo para limpar: reiniciar o backend já começa vazio.

## 11. Preparar uma contribuição

Partindo da branch `qa` atualizada:

```bash
git fetch origin
git switch qa
git pull --ff-only origin qa
git switch -c docs/minha-contribuicao
```

Após realizar e validar as alterações:

```bash
git diff --check
git status --short
```

Adicione apenas os arquivos relacionados à contribuição e registre um commit seguindo
[`CONTRIBUTING.md`](../CONTRIBUTING.md).

### Publicar a contribuição

Crie um fork de `Rede-ENois/enois-backend` na sua conta pelo GitHub. Não é necessário ter acesso de
escrita ao repositório original.

Configure o remote do seu fork (substitua `SEU_USUARIO`):

```bash
git remote add fork https://github.com/SEU_USUARIO/enois-backend.git
git remote -v
```

Se o remote `fork` já existir, reutilize-o.

Após adicionar apenas os arquivos pretendidos com `git add`, confira o conteúdo preparado e faça o
commit:

```bash
git diff --cached --check
git diff --cached --stat
git commit -m "docs: descreve minha contribuicao"
git push -u fork docs/minha-contribuicao
```

A mensagem do commit é ilustrativa: adapte-a à alteração.

Abra um Pull Request no GitHub selecionando:

- Repositório de destino: `Rede-ENois/enois-backend`.
- Branch de destino: `qa`.
- Origem: sua branch publicada no fork.

Preencha o template oficial de Pull Request, descreva as mudanças e informe quais verificações foram
executadas. Não inclua secrets, relatórios locais ou arquivos `.env`.

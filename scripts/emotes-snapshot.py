#!/usr/bin/env python3
"""Gera `src/main/resources/emotes/catalogo.json`, o retrato EMBARCADO do catalogo de figurinhas.

O retrato e a rede de seguranca: o pod que sobe sem rede (ou com o FFZ respondendo 522) serve
figurinha de verdade em vez de seletor vazio. Por isso ele carrega o mesmo conteudo que a montagem
ao vivo produziria — colecao global do BTTV e do FFZ, mais as mais usadas e as animadas — e nao
apenas a lista de populares.

As regras de juncao sao as de `EmoteCatalogService.rebuild()` e a ordem das fontes e a de
`EmotesConfig`: BTTV, FFZ-GLOBAL, FFZ-ANIMADAS, FFZ-POPULAR. Mudou a juncao la? Muda aqui tambem.

Uso: `python3 scripts/emotes-snapshot.py` (so rede; nenhuma credencial).
"""

import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SAIDA = Path(__file__).resolve().parent.parent / "src/main/resources/emotes/catalogo.json"

UA = "enois/1.0 (+https://enois.ia.br)"
TIMEOUT = 60

# Mesmos limites de `Emote`: codigo aceito no texto e identificador do CDN.
CODIGO = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
IDENTIFICADOR = re.compile(r"^[A-Za-z0-9]{1,40}$")

BTTV_GLOBAL = "https://api.betterttv.net/3/cached/emotes/global"
FFZ_GLOBAL = "https://api.frankerfacez.com/v1/set/global"
FFZ_LISTING = "https://api.frankerfacez.com/v1/emotes?sort=count-desc&per_page=200&page={page}"

PAGINAS_POPULARES = 4
PAGINAS_ANIMADAS = 2


def baixar(url):
    request = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=TIMEOUT) as resposta:
        if resposta.status != 200:
            raise RuntimeError(f"{url} respondeu {resposta.status}")
        return json.loads(resposta.read().decode("utf-8"))


def emote(code, provider, identificador, animated=False, popular_rank=None):
    """Valida como o record `Emote`; devolve None para o que nunca apareceria no texto."""
    if not isinstance(code, str) or not CODIGO.match(code):
        return None
    if not isinstance(identificador, str) or not IDENTIFICADOR.match(identificador):
        return None
    if popular_rank is not None and popular_rank < 1:
        return None
    return {
        "code": code,
        "provider": provider,
        "id": identificador,
        "animated": bool(animated),
        "popularRank": popular_rank,
    }


def id_do_ffz(emoticon):
    """`https://cdn.frankerfacez.com/emote/128054/2` -> `128054`."""
    urls = emoticon.get("urls") or {}
    for escala in ("2", "1", "4", "3"):
        url = urls.get(escala) or ""
        partes = [p for p in url.split("/") if p]
        for indice in range(len(partes) - 1, 0, -1):
            if partes[indice - 1] == "emote":
                return partes[indice]
    return None


def estaticos_ffz(emoticon):
    if emoticon.get("hidden") or emoticon.get("modifier"):
        return False
    return bool(emoticon.get("name")) and id_do_ffz(emoticon) is not None


def fonte_bttv():
    emotes = []
    for node in baixar(BTTV_GLOBAL):
        animated = bool(node.get("animated")) or node.get("imageType") == "gif"
        emotes.append(emote(node.get("code"), "BTTV", node.get("id"), animated))
    return [e for e in emotes if e]


def fonte_ffz_global():
    corpo = baixar(FFZ_GLOBAL)
    sets = corpo.get("sets") or {}
    padrao = corpo.get("default_sets") or list(sets.keys())
    emotes = []
    for ident in padrao:
        for emoticon in (sets.get(str(ident)) or {}).get("emoticons") or []:
            if estaticos_ffz(emoticon):
                animated = bool((emoticon.get("animated") or {}).get("1") or (emoticon.get("animated") or {}).get("2"))
                emotes.append(emote(emoticon["name"], "FFZ", id_do_ffz(emoticon), animated))
    return [e for e in emotes if e]


def fonte_ffz_animadas(paginas):
    emotes = []
    for pagina in range(1, paginas + 1):
        corpo = baixar(FFZ_LISTING.format(page=pagina) + "&animated=true")
        itens = corpo.get("emoticons") or []
        if not itens:
            break
        for emoticon in itens:
            if estaticos_ffz(emoticon):
                emotes.append(emote(emoticon["name"], "FFZ", id_do_ffz(emoticon), True))
    return [e for e in emotes if e]


def fonte_ffz_populares(paginas):
    emotes = []
    for pagina in range(1, paginas + 1):
        corpo = baixar(FFZ_LISTING.format(page=pagina))
        itens = corpo.get("emoticons") or []
        if not itens:
            break
        for emoticon in itens:
            if estaticos_ffz(emoticon):
                # A listagem popular NAO publica animacao: a posicao na ordem e o que ela traz.
                emotes.append(
                    emote(emoticon["name"], "FFZ", id_do_ffz(emoticon), False, len(emotes) + 1)
                )
    return [e for e in emotes if e]


def juntar(rodadas):
    """Mesma juncao de `EmoteCatalogService.rebuild()`."""
    por_codigo = {}
    posicoes = {}
    artes = set()

    for rodada in rodadas:
        for atual in rodada:
            arte = (atual["provider"], atual["id"])
            existente = por_codigo.get(atual["code"])
            if existente is not None:
                if (existente["provider"], existente["id"]) == arte:
                    existente["animated"] = existente["animated"] or atual["animated"]
                if atual["popularRank"] is not None:
                    posicoes.setdefault(atual["code"], atual["popularRank"])
                continue
            if arte in artes:
                continue
            artes.add(arte)
            por_codigo[atual["code"]] = dict(atual)
            if atual["popularRank"] is not None:
                posicoes.setdefault(atual["code"], atual["popularRank"])

    for code, rank in posicoes.items():
        por_codigo[code]["popularRank"] = rank

    # Ordem do seletor: populares por posicao, depois o resto em ordem alfabetica.
    return sorted(
        por_codigo.values(),
        key=lambda e: (0, e["popularRank"]) if e["popularRank"] is not None else (1, 0, e["code"].lower()),
    )


def main():
    rodadas = [
        fonte_bttv(),
        fonte_ffz_global(),
        fonte_ffz_animadas(PAGINAS_ANIMADAS),
        fonte_ffz_populares(PAGINAS_POPULARES),
    ]
    emotes = juntar(rodadas)
    if not emotes:
        print("Nenhuma fonte respondeu: o retrato nao foi reescrito.", file=sys.stderr)
        return 1

    retrato = {
        "source": "BTTV /3/cached/emotes/global + FFZ /v1/emotes?sort=count-desc (popular e animated)",
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "total": len(emotes),
        "emotes": emotes,
    }
    SAIDA.write_text(json.dumps(retrato, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    animadas = sum(1 for e in emotes if e["animated"])
    populares = sum(1 for e in emotes if e["popularRank"] is not None)
    print(f"{SAIDA}: {len(emotes)} figurinhas (populares {populares}, animadas {animadas})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
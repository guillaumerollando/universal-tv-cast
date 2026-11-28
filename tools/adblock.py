#!/usr/bin/env python3
"""
Fabrique la liste de domaines publicitaires du navigateur intégré : app/src/main/assets/adblock-hosts.txt.gz.

Source : EasyList et la Liste FR (les listes de Brave, uBlock Origin et Adblock Plus ; licence GPL-3.0 ou CC BY-SA 3.0,
https://easylist.to/pages/licence.html). On n'en garde que les règles « tout ce domaine » (||exemple.com^), sans
restriction à certains sites : c'est ce que le navigateur sait appliquer simplement, et l'essentiel des pubs et des
fenêtres surgissantes. Les sous-domaines d'un domaine déjà présent sont retirés.

À relancer avant une nouvelle version : python3 -I tools/adblock.py
"""
import gzip
import os
import re
import urllib.request

LISTS = [
    "https://easylist.to/easylist/easylist.txt",
    "https://easylist-downloads.adblockplus.org/liste_fr.txt",
]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "adblock-hosts.txt.gz")
RULE = re.compile(r"^\|\|([a-z0-9.-]+\.[a-z]{2,})\^(?:\$(.*))?$")
# Options sans effet ici (la règle vise tout le domaine, quel que soit le type de contenu).
OK_OPTS = {"third-party", "3p", "popup", "script", "image", "subdocument", "xmlhttprequest", "media", "all", "doc", "document"}


def domains(text):
    for line in text.splitlines():
        m = RULE.match(line.strip())
        if not m:
            continue
        opts = [o for o in (m.group(2) or "").split(",") if o]
        if any(o not in OK_OPTS for o in opts):  # domain=…, ~…, exceptions : trop fin pour nous
            continue
        yield m.group(1)


def main():
    found = set()
    for url in LISTS:
        req = urllib.request.Request(url, headers={"User-Agent": "universal-tv-cast-build"})
        text = urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "ignore")
        n = len(found)
        found.update(domains(text))
        print(f"{url} : +{len(found) - n}")
    keep = set()
    for d in sorted(found, key=lambda d: d.count(".")):
        parts = d.split(".")
        if any(".".join(parts[i:]) in keep for i in range(1, len(parts) - 1)):
            continue
        keep.add(d)
    data = "\n".join(sorted(keep)).encode()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, "wb", compresslevel=9) as f:
        f.write(data)
    print(f"{len(keep)} domaines → {os.path.relpath(OUT)} ({os.path.getsize(OUT) // 1024} Ko)")


if __name__ == "__main__":
    main()

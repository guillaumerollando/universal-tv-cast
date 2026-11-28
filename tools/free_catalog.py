#!/usr/bin/env python3
"""
Fabrique le catalogue « Films libres » de l'appli : app/src/main/assets/free-catalog.json.

Uniquement des films qu'on a le droit de diffuser :
- les films ouverts de la Blender Foundation (CC BY), affiches de Wikimedia Commons ;
- les longs métrages du domaine public d'Internet Archive (collection « feature_films », licence domaine public
  déclarée), les plus regardés, rangés par genre d'après leurs sujets.
L'appli ne joint aucun service : elle lit ce fichier, et les vidéos sont lues directement chez Internet Archive.

À relancer de temps en temps : python3 -I tools/free_catalog.py
"""
import json
import os
import re
import time
import urllib.parse
import urllib.request

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "free-catalog.json")
UA = {"User-Agent": "universal-tv-cast-build (catalogue libre)"}
WANT = 120  # films du domaine public gardés

# Films ouverts de Blender (sur Internet Archive pour la vidéo, affiche sur Wikimedia Commons).
BLENDER = [
    ("BigBuckBunny_124", "Content/big_buck_bunny_720p_surround.mp4", "Big Buck Bunny", 2008, "File:Big buck bunny poster big.jpg"),
    ("Sintel", "sintel-2048-surround_512kb.mp4", "Sintel", 2010, "File:Sintel poster.jpg"),
    ("ElephantsDream", "ed_1024_512kb.mp4", "Elephants Dream", 2006, "File:ElephantsDreamPoster.jpg"),
    ("Tears-of-Steel", None, "Tears of Steel", 2012, "File:Tos-poster.png"),
    ("CosmosLaundromatFirstCycle", None, "Cosmos Laundromat", 2015, "File:CosmosLaundromatPoster.jpg"),
]

# Genre d'après les sujets (le premier trouvé l'emporte).
GENRES = [
    ("horror", r"horror|zombie|vampire|monster|terror"),
    ("scifi", r"sci.?fi|science fiction|space|alien|robot"),
    ("noir", r"noir|crime|gangster|detective|mystery|thriller"),
    ("western", r"western|cowboy"),
    ("comedy", r"comed|funny|slapstick|chaplin|keaton|laurel"),
    ("adventure", r"adventure|action|war|swashbuckl"),
    ("animation", r"animat|cartoon"),
    ("drama", r"drama|romance|melodrama"),
]
# Titres écartés (contenu pour adultes, propagande, sujets douteux pour une appli tout public).
BANNED = re.compile(r"sex|nud|naked|nazi|propaganda|reefer|marihuana|marijuana|cocaine|exploitation|burlesque|strip|birth of a nation", re.I)


def get(url):
    for i in range(4):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40).read()
        except Exception as e:  # noqa: BLE001
            print("  nouvel essai :", e)
            time.sleep(3 * (i + 1))
    raise RuntimeError(url)


def commons(title):
    q = urllib.parse.urlencode(dict(action="query", prop="imageinfo", iiprop="url", iiurlwidth=500, format="json", titles=title))
    p = list(json.loads(get("https://commons.wikimedia.org/w/api.php?" + q))["query"]["pages"].values())[0]
    # Adresse stable, sans paramètres de suivi (mieux mise en cache chez Wikimedia).
    return p["imageinfo"][0]["thumburl"].split("?")[0].replace("thumb.wikimedia.org/wikipedia", "upload.wikimedia.org/wikipedia")


def words(t):
    return {w for w in re.findall(r"[a-z0-9]+", t.lower()) if len(w) > 3 and w not in ("the", "with", "from", "film", "movie")}


def commons_poster(title, year):
    """Affiche du film sur Wikimedia Commons (beaucoup de films du domaine public en ont une), ou None."""
    q = urllib.parse.urlencode(dict(action="query", list="search", srnamespace=6, srlimit=8, format="json",
                                    srsearch=f'{title} {year} poster'))
    try:
        hits = json.loads(get("https://commons.wikimedia.org/w/api.php?" + q))["query"]["search"]
    except Exception:  # noqa: BLE001
        return None
    want = words(title)
    for h in hits:
        name = h["title"]
        low = name.lower()
        if not low.endswith((".jpg", ".jpeg", ".png")) or "poster" not in low and "affiche" not in low:
            continue
        if want and len(want & words(name)) < max(1, (len(want) + 1) // 2):
            continue
        if str(year) not in name and len(want) < 2:
            continue
        try:
            return commons(name)
        except Exception:  # noqa: BLE001
            return None
    return None


def ia_image(ident, meta):
    """Image déposée avec le film (souvent son affiche), sinon une image du milieu du film (pas le générique)."""
    files = meta.get("files", [])
    # Images JPEG déposées avec le film (les PNG générés par Internet Archive sont des spectres du son, pas des affiches).
    own = [f["name"] for f in files if f.get("format") == "JPEG" and f.get("source") == "original" and "/" not in f["name"]
           and "thumb" not in f["name"].lower()]
    if own:
        return f"https://archive.org/download/{ident}/{urllib.parse.quote(own[0])}"
    thumbs = sorted(f["name"] for f in files if f.get("format") == "Thumbnail")
    if thumbs:
        return f"https://archive.org/download/{ident}/{urllib.parse.quote(thumbs[len(thumbs) * 2 // 5])}"
    return f"https://archive.org/services/img/{ident}"


def best_mp4(ident):
    """La vidéo MP4 la plus légère qui reste correcte (h.264 / 512Kb), ou None."""
    meta = json.loads(get(f"https://archive.org/metadata/{ident}"))
    files = [f for f in meta.get("files", []) if f.get("name", "").lower().endswith(".mp4")]
    if not files:
        return None, meta
    def score(f):
        fmt = (f.get("format") or "").lower()
        size = int(f.get("size") or 0)
        pref = 0 if "512kb" in fmt else 1 if "h.264" in fmt else 2
        return (pref, -size if pref == 1 else size)
    return sorted(files, key=score)[0]["name"], meta


def main():
    movies = []
    for ident, name, title, year, poster in BLENDER:
        if name is None:
            name, _ = best_mp4(ident)
            if name is None:
                print("pas de vidéo :", ident); continue
        movies.append(dict(id="b-" + ident, name=title, year=year, category="blender",
                           poster=commons(poster), url=f"https://archive.org/download/{ident}/{urllib.parse.quote(name)}"))
        time.sleep(1)
    q = urllib.parse.urlencode([("q", "collection:feature_films AND mediatype:movies AND licenseurl:*publicdomain*"),
                                ("fl[]", "identifier"), ("fl[]", "title"), ("fl[]", "year"), ("fl[]", "subject"),
                                ("sort[]", "downloads desc"), ("rows", "400"), ("output", "json")])
    docs = json.loads(get("https://archive.org/advancedsearch.php?" + q))["response"]["docs"]
    seen = set()
    for d in docs:
        if len(movies) >= WANT + len(BLENDER):
            break
        title = d.get("title") or ""
        title = (title[0] if isinstance(title, list) else str(title)).strip()
        year = d.get("year")
        year = year[0] if isinstance(year, list) else year
        subj = d.get("subject") or []
        subj = " ".join(subj if isinstance(subj, list) else [subj])
        # Titres propres seulement (pas de noms de fichiers ni de compilations), avec une année.
        if not year or not title or BANNED.search(title + " " + subj) or re.search(r"[_|]|\.mp4|\(.*-\d{4}\)|festival|collection|trailer", title, re.I):
            continue
        key = re.sub(r"\W+", "", title.lower())
        if key in seen:
            continue
        try:
            name, meta = best_mp4(d["identifier"])
        except Exception as e:  # noqa: BLE001
            print("écarté :", d["identifier"], e); continue
        if not name:
            continue
        seen.add(key)
        genre = next((g for g, rx in GENRES if re.search(rx, subj + " " + title, re.I)), "classic")
        desc = re.sub(r"<[^>]+>", " ", str((meta.get("metadata") or {}).get("description") or "")).strip()
        poster = commons_poster(title, str(year)[:4]) or ia_image(d["identifier"], meta)
        movies.append(dict(id="ia-" + d["identifier"], name=title, year=int(str(year)[:4]), category=genre,
                           poster=poster,
                           url=f"https://archive.org/download/{d['identifier']}/{urllib.parse.quote(name)}",
                           plot=re.sub(r"\s+", " ", desc)[:400] or None))
        print(len(movies), title, year, genre)
        time.sleep(0.5)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"generated": time.strftime("%Y-%m-%d"), "movies": movies}, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(movies)} films → {os.path.relpath(OUT)} ({os.path.getsize(OUT) // 1024} Ko)")


if __name__ == "__main__":
    main()

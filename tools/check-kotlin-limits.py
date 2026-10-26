#!/usr/bin/env python3
"""Garde-fou de taille : fichier < 500 lignes, fonction < 50 lignes.

Couvre le Kotlin (`.kt`, `.kts`) et le Python (`.py`).

Kotlin : commentaires, chaînes et littéraux sont neutralisés (mêmes numéros de
ligne), puis chaque déclaration est appariée par ses accolades : `fun` (simple,
`suspend`, génériques, receveur, corps-expression), `init`, `constructor`,
accesseurs `get`/`set`. Les noms entre backticks sont gérés. Python : `ast`.

Usage : python3 tools/check-kotlin-limits.py [racine] [--strict]
Sortie : 0 si conforme, 1 sinon (2 si la racine n'existe pas).
"""
import argparse
import ast
import bisect
import os
import re
import sys

MAX_FILE_LINES = 500          # refus dès 500 lignes
MAX_FUN_LINES = 50            # refus dès 50 lignes
KOTLIN_SUFFIXES = (".kt", ".kts")
PY_SUFFIXES = (".py",)
SKIP_DIRS = {".git", ".gradle", "build", ".idea", "tmp", "node_modules",
             "__pycache__", ".venv", "venv"}

# Dette mesurée : violations antérieures à la garde fiable, tolérées pour ne pas
# casser la CI de master. Clé = (chemin relatif, genre, nom) ; `--strict` les refuse.
KNOWN_VIOLATIONS = set()

MODIFIERS = {"private", "internal", "protected", "public", "override", "open",
             "final", "inline", "suspend", "abstract", "lateinit", "actual",
             "expect", "const", "external", "operator", "infix", "tailrec"}

TOKEN_RE = re.compile(r'//|/\*|"""|`|"|\'')
DECL_RE = re.compile(r"(?<![A-Za-z0-9_`])(fun|init|constructor|get|set)\b")
# Identifiant entre backticks : le nom reste lisible, seuls les caractères qui
# comptent pour la structure sont neutralisés (apostrophes et accents survivent).
ID_RISKY = "{}()[]<>;="


# --- Neutralisation des commentaires, chaînes et identifiants entre backticks ---
def _blank(chars, start, stop):
    """Remplace par des espaces, sans jamais toucher aux retours à la ligne."""
    for k in range(start, min(stop, len(chars))):
        if chars[k] != "\n":
            chars[k] = " "


def _skip_block(chars, text, start):
    """Neutralise un commentaire /* */ (imbrication Kotlin gérée)."""
    depth, i, n = 0, start, len(text)
    while i < n:
        two = text[i:i + 2]
        if two in ("/*", "*/"):
            depth += 1 if two == "/*" else -1
            _blank(chars, i, i + 2)
            i += 2
            if depth == 0:
                return i
            continue
        _blank(chars, i, i + 1)
        i += 1
    return n


def _skip_quoted(chars, text, start, quote):
    """Neutralise une chaîne ou un caractère sur une ligne."""
    i, n = start + 1, len(text)
    while i < n and text[i] != "\n":
        if text[i] == "\\":
            _blank(chars, i, i + 2)
            i += 2
            continue
        _blank(chars, i, i + 1)
        if text[i] == quote:
            return i + 1
        i += 1
    return i


def _skip_backtick(chars, text, start):
    """Neutralise les caractères dangereux d'un identifiant entre backticks."""
    line_end = text.find("\n", start)
    line_end = len(text) if line_end < 0 else line_end
    end = text.find("`", start + 1, line_end)
    if end < 0:
        _blank(chars, start, start + 1)           # backtick isolé (code invalide)
        return start + 1
    for k in range(start + 1, end):
        if chars[k] in ID_RISKY:
            chars[k] = " "
    return end + 1


def _blank_token(chars, text, match):
    """Neutralise le jeton trouvé et rend la position de reprise."""
    tok, i = match.group(0), match.start()
    if tok == "//":
        end = text.find("\n", i)
        end = len(text) if end < 0 else end
        _blank(chars, i, end)
        return end
    if tok == "/*":
        return _skip_block(chars, text, i)
    if tok == "`":
        return _skip_backtick(chars, text, i)
    if tok == '"""':
        end = text.find('"""', i + 3)
        end = len(text) if end < 0 else end + 3
        _blank(chars, i, end)
        return end
    return _skip_quoted(chars, text, i, tok)


def strip_strings_comments(text):
    """Neutralise commentaires et chaînes : même texte, mêmes numéros de ligne."""
    chars = list(text)
    pos = 0
    while True:
        match = TOKEN_RE.search(text, pos)
        if not match:
            break
        pos = max(_blank_token(chars, text, match), match.end())
    return "".join(chars)


# --- Petits outils de parcours ---
def _word_at(text, i):
    j = i
    while j < len(text) and (text[j].isalnum() or text[j] == "_"):
        j += 1
    return text[i:j]


def _skip_ws(text, i):
    n = len(text)
    while i < n and text[i] in " \t\r\n":
        i += 1
    return i


def _match(text, i, open_ch, close_ch):
    """Index du fermant qui équilibre l'ouvrant situé en `i` (None si absent)."""
    depth, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def _indent_at(text, offset):
    start = text.rfind("\n", 0, offset) + 1
    j = start
    while j < len(text) and text[j] in " \t":
        j += 1
    return j - start


def _next_indent(text, newline):
    """Indentation de la prochaine ligne non vide après `newline`."""
    n, j = len(text), newline + 1
    while j < n:
        k = text.find("\n", j)
        if k < 0:
            k = n
        line = text[j:k]
        if line.strip():
            return len(line) - len(line.lstrip())
        j = k + 1
    return None


def _line_of(text, offset):
    return text.count("\n", 0, offset) + 1


# --- Parseur Kotlin ---
def _find_params(text, i):
    """Index de la parenthèse ouvrante des paramètres (None si ce n'est pas une fun)."""
    depth, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch == "<":
            depth += 1
        elif ch == ">" and depth > 0 and text[i - 1] != "-":
            depth -= 1
        elif depth == 0 and ch == "(":
            return i
        elif depth == 0 and ch in "{};=":
            return None
        i += 1
    return None


def _name_before(text, paren, low):
    """Nom de la fonction juste avant `(` (backticks compris), None si anonyme."""
    j = paren - 1
    while j >= low and text[j] in " \t\r\n":
        j -= 1
    if j >= low and text[j] == "`":
        k = text.rfind("`", low, j)
        return text[k:j + 1] if k >= low else None
    end = j + 1
    while j >= low and (text[j].isalnum() or text[j] == "_"):
        j -= 1
    name = text[j + 1:end]
    return name if name and not name[0].isdigit() else None


def _body_start(text, i, base_indent):
    """Trouve le corps : ('{', index), ('=', index) ou (None, None) si abstrait."""
    depth, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch in "([":
            depth += 1
        elif ch in ")]":
            depth -= 1
        elif depth == 0 and ch == "{":
            return "{", i
        elif depth == 0 and ch == "=" and text[i + 1:i + 2] != "=":
            return "=", i
        elif depth == 0 and ch in ";}":
            return None, None
        elif depth == 0 and ch == "\n":
            nxt = _next_indent(text, i)
            if nxt is None or nxt <= base_indent:
                return None, None
        i += 1
    return None, None


def _expr_end(text, i, base_indent):
    """Fin du corps-expression d'une fonction (`= ...`), à l'indentation près."""
    depth, n = 0, len(text)
    while i < n:
        ch = text[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return i - 1
            depth -= 1
        elif ch == "\n" and depth == 0:
            nxt = _next_indent(text, i)
            if nxt is None or nxt <= base_indent:
                return i - 1
        i += 1
    return n - 1


def _span(text, start, body, name):
    """(nom, début, corps, fin) de la déclaration, None si non mesurable."""
    base = _indent_at(text, start)
    if text[body] == "{":
        end = _match(text, body, "{", "}")
    else:
        end = _expr_end(text, body, base)
    if end is None or end < start:
        return None
    return name, start, body, end


def _at_statement_start(text, start):
    """Vrai si le mot en `start` ouvre une déclaration de la ligne."""
    prefix = text[text.rfind("\n", 0, start) + 1:start].strip()
    return not prefix or prefix.split()[0] in MODIFIERS


def _parse_fun(text, start):
    i = _skip_ws(text, start + 3)
    if _word_at(text, i) == "interface":          # `fun interface` : pas une fonction
        return None
    paren = _find_params(text, i)
    if paren is None:
        return None
    name = _name_before(text, paren, start + 3)
    if name is None:                              # fonction anonyme `fun(...)`
        return None
    close = _match(text, paren, "(", ")")
    if close is None:
        return None
    _, body = _body_start(text, close + 1, _indent_at(text, start))
    return _span(text, start, body, name) if body is not None else None


def _parse_block(text, start, keyword, must):
    """Déclaration à corps en accolades (`init`) ou en paramètres (`constructor`)."""
    if not _at_statement_start(text, start):
        return None
    i = _skip_ws(text, start + len(keyword))
    if i >= len(text) or text[i] not in must:
        return None
    if text[i] == "(":
        close = _match(text, i, "(", ")")
        if close is None:
            return None
        _, i = _body_start(text, close + 1, _indent_at(text, start))
        if i is None:
            return None
    return _span(text, start, i, keyword)


def _parse_accessor(text, start, keyword):
    """Accesseur `get`/`set` de propriété (`get() = ...`, `set(v) { ... }`)."""
    if not _at_statement_start(text, start):
        return None
    i = _skip_ws(text, start + len(keyword))
    if i >= len(text) or text[i] not in "({=":
        return None
    if text[i] == "(":
        close = _match(text, i, "(", ")")
        if close is None:
            return None
        i = _skip_ws(text, close + 1)
        if text[i:i + 1] not in ("{", "="):
            return None
    return _span(text, start, i, keyword)


def _parse_decl(text, match):
    keyword = match.group(1)
    if keyword == "fun":
        return _parse_fun(text, match.start())
    if keyword == "constructor":
        return _parse_block(text, match.start(), keyword, "(")
    if keyword == "init":
        return _parse_block(text, match.start(), keyword, "{")
    return _parse_accessor(text, match.start(), keyword)


def kotlin_declarations(text):
    """Liste (nom, début, fin) de toutes les déclarations de fonction du fichier."""
    code = strip_strings_comments(text)
    found, header_end = [], -1
    for match in DECL_RE.finditer(code):
        if match.start() < header_end:     # mot-clé pris dans un en-tête (nom, type)
            continue
        span = _parse_decl(code, match)
        if span is None:
            continue
        name, start, body, end = span
        found.append((name, start, end))
        header_end = max(header_end, body)
    return code, found


# --- Contrôles ---
class Violation:
    def __init__(self, kind, name, line, size, limit):
        self.kind, self.name, self.line = kind, name, line
        self.size, self.limit = size, limit

    def text(self):
        if self.kind == "fichier":
            return f"fichier {self.size} lignes (max {self.limit})"
        return f"fonction {self.size} lignes (max {self.limit}) — {self.name}"


def check_kotlin(text):
    lines = text.splitlines()
    problems = [Violation("fichier", "", 1, len(lines), MAX_FILE_LINES)] \
        if len(lines) >= MAX_FILE_LINES else []
    code, spans = kotlin_declarations(text)
    for name, start, end in spans:
        size = _line_of(code, end) - _line_of(code, start) + 1
        if size >= MAX_FUN_LINES:
            problems.append(Violation("fonction", name, _line_of(code, start),
                                      size, MAX_FUN_LINES))
    return problems


def check_python(text):
    lines = text.splitlines()
    problems = [Violation("fichier", "", 1, len(lines), MAX_FILE_LINES)] \
        if len(lines) >= MAX_FILE_LINES else []
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return problems
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            size = (node.end_lineno or node.lineno) - node.lineno + 1
            if size >= MAX_FUN_LINES:
                problems.append(Violation("fonction", node.name, node.lineno,
                                          size, MAX_FUN_LINES))
    return problems


def check_file(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    if path.endswith(PY_SUFFIXES):
        return check_python(text)
    return check_kotlin(text)


def _iter_sources(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name.endswith(KOTLIN_SUFFIXES + PY_SUFFIXES):
                yield os.path.join(dirpath, name)


def scan(root):
    total, findings = 0, []
    for path in _iter_sources(root):
        total += 1
        problems = check_file(path)
        if problems:
            findings.append((os.path.relpath(path, root).replace(os.sep, "/"),
                             problems))
    return total, findings


def _report(total, findings, strict):
    print(f"check-kotlin-limits : {total} fichier(s) .kt/.kts/.py inspecté(s)")
    print(f"règles : fichier < {MAX_FILE_LINES} lignes, fonction/composable "
          f"< {MAX_FUN_LINES} lignes")
    blocking, tolerated = [], []
    for relpath, problems in findings:
        for problem in problems:
            known = (relpath, problem.kind, problem.name) in KNOWN_VIOLATIONS
            (tolerated if known and not strict else blocking).append((relpath, problem))
    if tolerated:
        print(f"\n{len(tolerated)} dépassement(s) connu(s), toléré(s) hors --strict :")
        for relpath, problem in tolerated:
            print(f"  {relpath}:{problem.line} — {problem.text()}")
    if not blocking:
        print("\nOK — aucun dépassement." if not tolerated
              else "\nOK — aucun dépassement nouveau.")
        return 0
    print(f"\n{len(blocking)} dépassement(s) dans "
          f"{len({path for path, _ in blocking})} fichier(s) :")
    for relpath, problem in blocking:
        print(f"  {relpath}:{problem.line} — {problem.text()}")
    return 1


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Garde-fou : fichier < 500 lignes, fonction < 50 lignes.")
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument("--strict", action="store_true",
                        help="refuse aussi les dépassements déjà connus")
    args = parser.parse_args(argv)
    if not os.path.isdir(args.root):
        print(f"racine introuvable : {args.root}", file=sys.stderr)
        return 2
    total, findings = scan(args.root)
    return _report(total, findings, args.strict)


if __name__ == "__main__":
    sys.exit(main())
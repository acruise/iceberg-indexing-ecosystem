#!/usr/bin/env python3
"""Bring a Google Doc in line with a Markdown file by minimal edits.

The proposal's Markdown is canonical, and a Google Doc copy exists for review
comments. Replacing the doc's body wholesale would orphan every comment, so this
script instead computes the smallest set of Docs API requests that turns the
doc into what Google's Markdown importer would have produced from the file.
Text that has not changed is never touched, so comments anchored to it survive;
a comment is lost only when every word it is anchored to is rewritten.

The script does not call Google itself. It reads the JSON that the Docs API's
documents.get returns (the Google Docs connector's read_doc), and writes the
arguments for one documents.batchUpdate call (update_doc), guarded by the
doc's revisionId. Sync is iterative: run, apply, read again, run again, until
the plan is empty. Each pass does one kind of work:

  1. structure  table rows inserted or deleted, new tables created
  2. text       paragraphs inserted, deleted, or edited word by word
  3. style      named styles, bullets, bold/italic/code/link runs

Usage:
  gdoc_sync.py plan  DOC.json FILE.md [-o REQUESTS.json]   print a summary, write the batch
  gdoc_sync.py model FILE.md                               print the Markdown model (debugging)

DOC.json is the documents.get JSON, either bare or wrapped as {"content": ...}.

Conventions copied from Google's Markdown importer, as observed on the
proposal's doc: an empty paragraph separates consecutive blocks unless one of
them is a heading, and a paragraph runs straight into a list that follows it;
inline code is Roboto Mono in dark green; code blocks are Docs code-block
elements, marked by U+E907, whose lines are 9pt Roboto Mono. Code blocks that
this script adds are plain 9pt Roboto Mono paragraphs, since the API cannot
create the code-block element; they read the same but lack its shading.
"""

import argparse
import difflib
import json
import re
import subprocess
import sys

CODE_MARK = ""
MONO = "Roboto Mono"
CODE_GREEN = {"red": 0.09411765, "green": 0.5019608, "blue": 0.21960784}
CODE_GRAY = {"red": 0.21568628, "green": 0.2784314, "blue": 0.30980393}
LINK_BLUE = {"red": 0.06666667, "green": 0.33333334, "blue": 0.8}


def u16(s):
    return len(s.encode("utf-16-le")) // 2


# ---------------------------------------------------------------------------
# Character styles. A style is a tuple (bold, italic, code, link) where code is
# None, "inline" or "block" and link is a URL or None.

PLAIN = (False, False, None, None)


class Para:
    def __init__(self, kind="NORMAL_TEXT", bullet=None):
        self.kind = kind          # NORMAL_TEXT, HEADING_n, or CODE
        self.bullet = bullet      # None or (ordered: bool, level: int)
        self.chars = []           # list of (char, style)
        # doc side only
        self.start = None
        self.end = None
        self.idx = []             # doc index of each char in self.chars
        self.list_id = None

    @property
    def text(self):
        return "".join(c for c, _ in self.chars)

    def key(self):
        return self.text

    def __repr__(self):
        b = "" if not self.bullet else (" [%s%d]" % ("ol" if self.bullet[0] else "ul", self.bullet[1]))
        return "%s%s: %r" % (self.kind, b, self.text[:70])


class Table:
    def __init__(self, rows):
        self.rows = rows          # list of rows, each a list of cells, each a list of Para
        self.start = None
        self.end = None

    def key(self):
        return "\x00TABLE\x00" + "|".join(c[0].text if c else "" for c in self.rows[0])

    def row_key(self, r):
        return "|".join(" ".join(p.text for p in cell) for cell in self.rows[r])

    def __repr__(self):
        return "TABLE %dx%d: %s" % (len(self.rows), len(self.rows[0]), self.key()[7:70])


# ---------------------------------------------------------------------------
# Markdown model, via pandoc's JSON AST.

def inlines_to_chars(inls, style, out):
    bold, italic, code, link = style
    for il in inls:
        t, c = il["t"], il.get("c")
        if t == "Str":
            out.extend((ch, style) for ch in c)
        elif t in ("Space", "SoftBreak"):
            out.append((" ", style))
        elif t == "LineBreak":
            out.append(("\x0b", style))
        elif t == "Strong":
            inlines_to_chars(c, (True, italic, code, link), out)
        elif t == "Emph":
            inlines_to_chars(c, (bold, True, code, link), out)
        elif t == "Code":
            out.extend((ch, (bold, italic, "inline", link)) for ch in c[1])
        elif t == "Link":
            inlines_to_chars(c[1], (bold, italic, code, LINK_RESOLVER(c[2][0])), out)
        elif t in ("Span", "Strikeout", "Underline", "Superscript", "Subscript", "SmallCaps"):
            inlines_to_chars(c[-1] if t == "Span" else c, style, out)
        elif t == "Quoted":
            q = '"' if c[0]["t"] == "DoubleQuote" else "'"
            out.append((q, style)); inlines_to_chars(c[1], style, out); out.append((q, style))
        elif t in ("RawInline",):
            pass
        else:
            raise SystemExit("unsupported inline %s" % t)


def LINK_RESOLVER(url):
    return url


def make_link_resolver(md_path, base):
    """Relative links mean nothing in a Google Doc, so point them at the repository host."""
    import os
    import posixpath
    top = subprocess.run(["git", "-C", os.path.dirname(os.path.abspath(md_path)), "rev-parse", "--show-toplevel"],
                         capture_output=True, text=True, check=True).stdout.strip()
    md_dir = posixpath.dirname(os.path.relpath(os.path.abspath(md_path), top).replace(os.sep, "/"))

    def resolve(url):
        if re.match(r"^[a-z][a-z0-9+.-]*:", url, re.I) or url.startswith("#") or not base:
            return url
        return base.rstrip("/") + "/" + posixpath.normpath(posixpath.join(md_dir, url))
    return resolve


def para_from_inlines(inls, kind="NORMAL_TEXT", bullet=None):
    p = Para(kind, bullet)
    inlines_to_chars(inls, PLAIN, p.chars)
    return p


def md_model(path):
    ast = json.loads(subprocess.run(
        ["pandoc", "-f", "gfm", "-t", "json", path],
        capture_output=True, text=True, check=True).stdout)
    blocks = []   # top-level groups: each is (group_kind, [elements])

    def list_items(items, ordered, level, out):
        for item in items:
            first = True
            for b in item:
                if b["t"] in ("Plain", "Para"):
                    out.append(para_from_inlines(b["c"], bullet=(ordered, level)) if first
                               else para_from_inlines(b["c"], bullet=(ordered, level)))
                    first = False
                elif b["t"] == "BulletList":
                    list_items(b["c"], False, level + 1, out)
                elif b["t"] == "OrderedList":
                    list_items(b["c"][1], True, level + 1, out)
                else:
                    raise SystemExit("unsupported block in list item: %s" % b["t"])

    def cell_paras(cell_blocks):
        ps = []
        for b in cell_blocks:
            if b["t"] in ("Plain", "Para"):
                ps.append(para_from_inlines(b["c"]))
            else:
                raise SystemExit("unsupported block in table cell: %s" % b["t"])
        return ps or [Para()]

    for b in ast["blocks"]:
        t, c = b["t"], b.get("c")
        if t == "Header":
            blocks.append(("heading", [para_from_inlines(c[2], "HEADING_%d" % c[0])]))
        elif t in ("Para", "Plain"):
            blocks.append(("para", [para_from_inlines(c)]))
        elif t in ("BulletList", "OrderedList"):
            items = c if t == "BulletList" else c[1]
            loose = any(item and item[0]["t"] == "Para" for item in items)
            out = []
            for k, item in enumerate(items):
                if loose and k:
                    out.append(Para())
                list_items([item], t == "OrderedList", 0, out)
            blocks.append(("looselist" if loose else "list", out))
        elif t == "CodeBlock":
            lines = c[1].split("\n")
            ps = []
            for line in lines:
                p = Para("CODE")
                p.chars = [(ch, (False, False, "block", None)) for ch in line]
                ps.append(p)
            blocks.append(("code", ps))
        elif t == "Table":
            head, bodies = c[3], c[4]
            rows = []
            for r in head[1]:
                cells = []
                for cell in r[1]:
                    ps = cell_paras(cell[4])
                    for p in ps:
                        p.chars = [(ch, (True,) + st[1:]) for ch, st in p.chars]
                    cells.append(ps)
                rows.append(cells)
            for body in bodies:
                for r in body[3]:
                    rows.append([cell_paras(cell[4]) for cell in r[1]])
            blocks.append(("table", [Table(rows)]))
        elif t == "BlockQuote":
            # Block quotes (the proposal's TODO notes) become ordinary
            # paragraphs; their bold lead-in carries the distinction.
            for sub in c:
                if sub["t"] in ("Para", "Plain"):
                    blocks.append(("para", [para_from_inlines(sub["c"])]))
                else:
                    raise SystemExit("unsupported block in block quote: %s" % sub["t"])
        elif t in ("HorizontalRule", "RawBlock"):
            continue
        else:
            raise SystemExit("unsupported block %s" % t)

    # Separator paragraphs, as the importer places them.
    elems = []
    prev = None
    for kind, els in blocks:
        if prev is not None and prev != "heading" and kind != "heading":
            # a tight list follows a lead-in paragraph ending in a colon directly
            if not (prev == "para" and kind == "list" and elems[-1].text.rstrip().endswith(":")):
                elems.append(Para())
        elems.extend(els)
        prev = kind
    return elems


# ---------------------------------------------------------------------------
# Doc model.

def doc_style(ts, para_is_code):
    font = (ts.get("weightedFontFamily") or {}).get("fontFamily")
    code = None
    if font in (MONO, "Courier New", "Consolas", "Source Code Pro"):
        code = "block" if para_is_code else "inline"
    link = (ts.get("link") or {}).get("url")
    return (bool(ts.get("bold")), bool(ts.get("italic")), code, link)


def para_from_doc(pe, start, end, lists):
    p = pe["paragraph"]
    ps = p["paragraphStyle"]
    runs = [r["textRun"] for r in p["elements"] if "textRun" in r]
    text_runs = [r for r in runs if r["content"].strip(CODE_MARK + "\n")]
    is_code = bool(text_runs) and all(
        (r["textStyle"].get("weightedFontFamily") or {}).get("fontFamily") == MONO
        and (r["textStyle"].get("fontSize") or {}).get("magnitude") == 9
        for r in text_runs)
    kind = ps.get("namedStyleType", "NORMAL_TEXT")
    if is_code and kind == "NORMAL_TEXT":
        kind = "CODE"
    bullet = None
    if p.get("bullet"):
        lid = p["bullet"]["listId"]
        level = p["bullet"].get("nestingLevel", 0)
        nl = lists[lid]["listProperties"]["nestingLevels"][level]
        ordered = bool(nl.get("glyphType")) and nl.get("glyphType") != "GLYPH_TYPE_UNSPECIFIED"
        bullet = (ordered, level)
    para = Para(kind, bullet)
    para.start, para.end = start, end
    para.list_id = (p.get("bullet") or {}).get("listId")
    for r in p["elements"]:
        if "textRun" not in r:
            # a chip, image, footnote reference: occupies indexes but has no text we model
            continue
        tr = r["textRun"]
        i = r["startIndex"]
        st = doc_style(tr["textStyle"], kind == "CODE")
        for ch in tr["content"]:
            n = u16(ch)
            if ch != CODE_MARK and ch != "\n":
                para.chars.append((ch, st))
                para.idx.append(i)
            i += n
    return para


def doc_model(doc):
    if "content" in doc and "tabs" not in doc:
        doc = doc["content"]
        if isinstance(doc, str):
            doc = json.loads(doc)
    tab = doc["tabs"][0]
    dt = tab["documentTab"]
    lists = dt.get("lists", {})
    elems = []
    for e in dt["body"]["content"]:
        if "paragraph" in e:
            elems.append(para_from_doc(e, e["startIndex"], e["endIndex"], lists))
        elif "table" in e:
            rows = []
            for row in e["table"]["tableRows"]:
                cells = []
                for cell in row["tableCells"]:
                    cells.append([para_from_doc(ce, ce["startIndex"], ce["endIndex"], lists)
                                  for ce in cell["content"] if "paragraph" in ce])
                rows.append(cells)
            t = Table(rows)
            t.start, t.end = e["startIndex"], e["endIndex"]
            elems.append(t)
    return doc["revisionId"], tab["tabProperties"]["tabId"], elems


# ---------------------------------------------------------------------------
# Alignment.

def similarity(a, b):
    if isinstance(a, Table) or isinstance(b, Table):
        if not (isinstance(a, Table) and isinstance(b, Table)):
            return 0.0
        if a.key() == b.key():
            return 1.0
        # A table the structure phase has just inserted is empty; it matches
        # the new table of the same shape so the text phase can fill it.
        def empty(t):
            return all(not p.text for row in t.rows for cell in row for p in cell)
        def shape(t):
            return (len(t.rows), len(t.rows[0]))
        if shape(a) == shape(b) and (empty(a) or empty(b)):
            return 0.9
        return 0.0
    if a.text == b.text:
        return 1.0
    if not a.text or not b.text:
        return 0.0
    sm = difflib.SequenceMatcher(None, a.text.split(), b.text.split(), autojunk=False)
    return sm.ratio()


def fuzzy_pairs(old, new, threshold=0.5):
    """Order-preserving pairing of two short element lists by similarity (DP)."""
    n, m = len(old), len(new)
    score = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            s = similarity(old[i], new[j])
            best = max(score[i + 1][j], score[i][j + 1])
            if s >= threshold:
                best = max(best, s + score[i + 1][j + 1])
            score[i][j] = best
    pairs, i, j = [], 0, 0
    while i < n and j < m:
        s = similarity(old[i], new[j])
        if s >= threshold and abs(score[i][j] - (s + score[i + 1][j + 1])) < 1e-9:
            pairs.append((i, j)); i += 1; j += 1
        elif score[i + 1][j] >= score[i][j + 1]:
            i += 1
        else:
            j += 1
    return pairs


def align(old, new):
    """Return a list of (old_elem or None, new_elem or None) in document order."""
    sm = difflib.SequenceMatcher(None, [e.key() for e in old], [e.key() for e in new], autojunk=False)
    out = []
    for op, i1, i2, j1, j2 in sm.get_opcodes():
        if op == "equal":
            out.extend(zip(old[i1:i2], new[j1:j2]))
            continue
        a, b = old[i1:i2], new[j1:j2]
        pairs = fuzzy_pairs(a, b) if a and b else []
        pi = pj = 0
        for (x, y) in pairs + [(len(a), len(b))]:
            out.extend((e, None) for e in a[pi:x])
            out.extend((None, e) for e in b[pj:y])
            if x < len(a):
                out.append((a[x], b[y]))
            pi, pj = x + 1, y + 1
    return out


# ---------------------------------------------------------------------------
# Planning.

TOKEN = re.compile(r"\s+|\w+|[^\w\s]", re.UNICODE)


def word_edits(op, np, tab):
    """Requests editing paragraph op (doc) into np (desired), highest index first."""
    a, b = op.text, np.text
    ta, tb = TOKEN.findall(a), TOKEN.findall(b)
    offs_a = [0]
    for t in ta:
        offs_a.append(offs_a[-1] + len(t))
    sm = difflib.SequenceMatcher(None, ta, tb, autojunk=False)
    edits = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        c1, c2 = offs_a[i1], offs_a[i2]
        if c1 < len(op.idx):
            pos = op.idx[c1]
        else:
            pos = (op.idx[-1] + u16(a[-1])) if op.idx else op.start + (1 if op.text == "" and False else 0)
            if not op.idx:
                pos = op.start
                # skip a leading code mark if present
        dend = (op.idx[c2 - 1] + u16(a[c2 - 1])) if c2 > c1 else pos
        ins = "".join(tb[j1:j2])
        edits.append((pos, dend, ins))
    reqs = []
    for pos, dend, ins in sorted(edits, key=lambda e: -e[0]):
        if dend > pos:
            reqs.append({"deleteContentRange": {"range": {"startIndex": pos, "endIndex": dend, "tabId": tab}}})
        if ins:
            reqs.append({"insertText": {"location": {"index": pos, "tabId": tab}, "text": ins}})
    return reqs


def leaf_paras(elems):
    for e in elems:
        if isinstance(e, Para):
            yield e
        else:
            for row in e.rows:
                for cell in row:
                    yield from cell


class Plan:
    def __init__(self, tab):
        self.tab = tab
        self.groups = []      # (anchor index, [requests]) applied highest anchor first
        self.notes = []

    def add(self, anchor, reqs, note=None):
        if reqs:
            self.groups.append((anchor, reqs))
        if note:
            self.notes.append(note)

    @staticmethod
    def _reach(reqs):
        """Highest index a group touches; groups run from the highest reach down."""
        hi = 0
        for r in reqs:
            v = next(iter(r.values()))
            if "location" in v:
                hi = max(hi, v["location"]["index"])
            if "range" in v:
                hi = max(hi, v["range"]["endIndex"] - 1, v["range"]["startIndex"])
            if "tableCellLocation" in v:
                hi = max(hi, v["tableCellLocation"]["tableStartLocation"]["index"])
        return hi

    def requests(self):
        # On a tie, a group that edits inside a paragraph runs before one that inserts
        # whole paragraphs at that paragraph's start, which would shift the edit.
        order = sorted(range(len(self.groups)),
                       key=lambda k: (-max(self.groups[k][0] if self.groups[k][0] >= 10 ** 12 else 0,
                                           self._reach(self.groups[k][1])),
                                      any(r.get("insertText", {}).get("text", "").endswith("\n")
                                          for r in self.groups[k][1])))
        out = []
        for k in order:
            out.extend(self.groups[k][1])
        return out


def plan_structure(pairs, plan):
    tab = plan.tab
    for i, (o, n) in enumerate(pairs):
        if isinstance(n, Table) and o is None:
            # A new table whose predecessor is itself new waits for the text
            # phase to create that predecessor; inserting it now would put it
            # after the last surviving paragraph, too early in the doc.
            if i > 0 and pairs[i - 1][0] is None:
                continue
            # new table: insert after the previous doc paragraph
            prev = next((p for p, _ in reversed(pairs[:i]) if p is not None), None)
            if not isinstance(prev, Para):
                raise SystemExit("cannot place new table %r" % n)
            at = prev.end - 1
            plan.add(at, [{"insertTable": {"rows": len(n.rows), "columns": len(n.rows[0]),
                                           "location": {"index": at, "tabId": tab}}}],
                     "insert table %r" % n)
        elif isinstance(o, Table) and isinstance(n, Table):
            if len(o.rows[0]) != len(n.rows[0]):
                raise SystemExit("column count changed in %r; edit the doc by hand" % n)
            if len(o.rows) == len(n.rows):
                continue
            ok = [o.row_key(r) for r in range(len(o.rows))]
            nk = [n.row_key(r) for r in range(len(n.rows))]
            sm = difflib.SequenceMatcher(None, ok, nk, autojunk=False)
            reqs = []
            loc = {"index": o.start, "tabId": tab}
            ops = []
            for tag, i1, i2, j1, j2 in sm.get_opcodes():
                if tag == "equal":
                    continue
                common = min(i2 - i1, j2 - j1)
                # rows beyond the common part are inserted or deleted; common rows are edited later
                for r in range(i1 + common, i2):
                    ops.append(("del", r))
                for k in range(j2 - j1 - common):
                    ops.append(("ins", i1 + common - 1))
            # apply from the bottom up so row indexes stay valid
            for kind, r in sorted(ops, key=lambda x: -x[1]):
                if kind == "del":
                    reqs.append({"deleteTableRow": {"tableCellLocation": {
                        "tableStartLocation": loc, "rowIndex": r, "columnIndex": 0}}})
                else:
                    reqs.append({"insertTableRow": {"tableCellLocation": {
                        "tableStartLocation": loc, "rowIndex": max(r, 0), "columnIndex": 0},
                        "insertBelow": r >= 0}})
            plan.add(o.start, reqs, "table %r: %d rows -> %d" % (n, len(o.rows), len(n.rows)))


def plan_text(pairs, plan):
    tab = plan.tab
    # deletions of whole elements
    for i, (o, n) in enumerate(pairs):
        if o is not None and n is None:
            if isinstance(o, Para) and o.text == "" and not o.bullet and o.kind == "NORMAL_TEXT":
                # The importer's choice of blank separators before lists is not fully
                # predictable, and removing a blank changes nothing a reader cares about.
                continue
            if isinstance(o, Table):
                plan.add(o.start, [{"deleteContentRange": {"range": {
                    "startIndex": o.start, "endIndex": o.end, "tabId": tab}}}], "delete %r" % o)
                continue
            nxt = pairs[i + 1][0] if i + 1 < len(pairs) else None
            if isinstance(nxt, Table) or nxt is None:
                # the paragraph before a table, or the last one, keeps its newline
                if o.idx:
                    s, e = o.start, o.end - 1
                    plan.add(s, [{"deleteContentRange": {"range": {"startIndex": s, "endIndex": e, "tabId": tab}}}],
                             "empty %r" % o)
            else:
                plan.add(o.start, [{"deleteContentRange": {"range": {
                    "startIndex": o.start, "endIndex": o.end, "tabId": tab}}}], "delete %r" % o)
    # insertions, grouped into runs of consecutive new paragraphs
    runs = []
    i = 0
    while i < len(pairs):
        o, n = pairs[i]
        if o is None and isinstance(n, Para):
            j = i
            run = []
            while j < len(pairs) and pairs[j][0] is None and isinstance(pairs[j][1], Para):
                run.append(pairs[j][1]); j += 1
            nxt = next((p for p, _ in pairs[j:] if p is not None), None)
            prev = next((p for p, _ in reversed(pairs[:i]) if p is not None), None)
            text = "\n".join(p.text for p in run)
            if isinstance(nxt, Para):
                at = nxt.start
                body = text + "\n"
            elif isinstance(prev, Para):
                at = prev.end - 1
                body = "\n" + text
            else:
                raise SystemExit("cannot place inserted paragraphs starting %r" % run[0])
            runs.append((at, [{"insertText": {"location": {"index": at, "tabId": tab}, "text": body}}],
                         "insert %d paragraphs before %r" % (len(run), nxt)))
            i = j
        else:
            i += 1
    # Two runs can share an anchor when a new table separates them. Requests at
    # one index land in front of those applied before them, so the later run
    # must be applied first; adding runs in reverse keeps that order through
    # the stable sort in Plan.requests.
    for at, reqs, note in reversed(runs):
        plan.add(at, reqs, note)
    # word-level edits in paired paragraphs and table cells
    for o, n in pairs:
        if isinstance(o, Para) and isinstance(n, Para):
            if o.text != n.text:
                plan.add(o.start, word_edits(o, n, tab), "edit %r" % n)
        elif isinstance(o, Table) and isinstance(n, Table):
            for r, (orow, nrow) in enumerate(zip(o.rows, n.rows)):
                for c, (ocell, ncell) in enumerate(zip(orow, nrow)):
                    if len(ocell) != len(ncell):
                        raise SystemExit("cell (%d,%d) of %r has a different paragraph count" % (r, c, n))
                    for op, np in zip(ocell, ncell):
                        if op.text != np.text:
                            if op.text == "":
                                plan.add(op.start, [{"insertText": {"location": {"index": op.start, "tabId": tab},
                                                                    "text": np.text}}], "fill cell (%d,%d)" % (r, c))
                            else:
                                plan.add(op.start, word_edits(op, np, tab), "edit cell (%d,%d) of %r" % (r, c, n))


def text_style_req(s, e, style, tab):
    bold, italic, code, link = style
    ts = {}
    if bold:
        ts["bold"] = True
    if italic:
        ts["italic"] = True
    if code:
        ts["weightedFontFamily"] = {"fontFamily": MONO, "weight": 400}
    if code == "block":
        ts["fontSize"] = {"magnitude": 9, "unit": "PT"}
        ts["foregroundColor"] = {"color": {"rgbColor": CODE_GRAY}}
    elif link:
        ts["foregroundColor"] = {"color": {"rgbColor": LINK_BLUE}}
    elif code == "inline":
        ts["foregroundColor"] = {"color": {"rgbColor": CODE_GREEN}}
    if link:
        ts["link"] = {"url": link}
        ts["underline"] = True
    return {"updateTextStyle": {"range": {"startIndex": s, "endIndex": e, "tabId": tab}, "textStyle": ts,
                                "fields": "bold,italic,weightedFontFamily,fontSize,foregroundColor,link,underline"}}


def plan_style(pairs, plan):
    tab = plan.tab
    flat = []
    for o, n in pairs:
        if isinstance(o, Para) and isinstance(n, Para):
            flat.append((o, n))
        elif isinstance(o, Table):
            for orow, nrow in zip(o.rows, n.rows):
                for ocell, ncell in zip(orow, nrow):
                    flat.extend(zip(ocell, ncell))
    para_reqs = []
    for k, (o, n) in enumerate(flat):
        rng = {"startIndex": o.start, "endIndex": o.end, "tabId": tab}
        nk = "NORMAL_TEXT" if n.kind == "CODE" else n.kind
        ok = "NORMAL_TEXT" if o.kind == "CODE" else o.kind
        reqs = []
        if nk != ok:
            reqs.append({"updateParagraphStyle": {"range": rng, "paragraphStyle": {"namedStyleType": nk},
                                                  "fields": "namedStyleType"}})
        if n.kind == "CODE" and o.kind != "CODE" and n.text:
            reqs.append({"updateParagraphStyle": {"range": rng, "paragraphStyle": {
                "spaceAbove": {"magnitude": 0, "unit": "PT"}, "spaceBelow": {"magnitude": 0, "unit": "PT"}},
                "fields": "spaceAbove,spaceBelow"}})
        if n.bullet != o.bullet:
            if o.bullet:
                reqs.append({"deleteParagraphBullets": {"range": rng}})
                reqs.append({"updateParagraphStyle": {"range": rng, "paragraphStyle": {
                    "indentStart": {"magnitude": 0, "unit": "PT"}, "indentFirstLine": {"magnitude": 0, "unit": "PT"}},
                    "fields": "indentStart,indentFirstLine"}})
            if n.bullet:
                if n.bullet[1] != 0:
                    plan.notes.append("nested bullet not created for %r; set its level by hand" % n)
                reqs.append({"createParagraphBullets": {"range": rng, "bulletPreset":
                    "NUMBERED_DECIMAL_ALPHA_ROMAN" if n.bullet[0] else "BULLET_DISC_CIRCLE_SQUARE"}})
        if reqs:
            para_reqs.extend(reqs)
            plan.notes.append("paragraph style %r" % n)
        # character runs
        if len(o.chars) != len(n.chars):
            continue
        i = 0
        while i < len(o.chars):
            if o.chars[i][1] == n.chars[i][1]:
                i += 1
                continue
            j = i
            while j < len(o.chars) and o.chars[j][1] != n.chars[j][1] and n.chars[j][1] == n.chars[i][1] \
                    and (j == i or o.idx[j] == o.idx[j - 1] + u16(o.chars[j - 1][0])):
                j += 1
            s, e = o.idx[i], o.idx[j - 1] + u16(o.chars[j - 1][0])
            plan.add(s, [text_style_req(s, e, n.chars[i][1], tab)])
            i = j
    if para_reqs:
        # paragraph-level requests shift no indexes, so they can run first as one group
        plan.groups.insert(0, (10 ** 12, coalesce(para_reqs)))


def coalesce(reqs):
    """Merge paragraph-range requests of one kind and payload over adjacent paragraphs.

    Merging also matters for correctness: bulleting adjacent paragraphs in one request
    makes them one list, so a new numbered list counts 1, 2, 3 rather than 1, 1, 1.
    """
    order = ["updateParagraphStyle", "deleteParagraphBullets", "createParagraphBullets"]
    out = []
    for kind in order:
        groups = {}
        for r in reqs:
            if kind not in r:
                continue
            v = dict(r[kind])
            rng = v.pop("range")
            sig = json.dumps(v, sort_keys=True)
            groups.setdefault(sig, []).append((rng["startIndex"], rng["endIndex"], rng["tabId"], v))
        for sig, items in groups.items():
            items.sort()
            merged = []
            for st, en, tab, v in items:
                if merged and merged[-1][1] == st:
                    merged[-1][1] = en
                else:
                    merged.append([st, en, tab, v])
            for st, en, tab, v in merged:
                body = {"range": {"startIndex": st, "endIndex": en, "tabId": tab}}
                body.update(v)
                out.append({kind: body})
    # deleting bullets must precede the indent reset that follows it
    dels = [r for r in out if "deleteParagraphBullets" in r]
    rest = [r for r in out if "deleteParagraphBullets" not in r]
    styles = [r for r in rest if "updateParagraphStyle" in r]
    creates = [r for r in rest if "createParagraphBullets" in r]
    return dels + styles + creates


def plan_sync(doc, md_path, link_base=None):
    global LINK_RESOLVER
    if link_base:
        LINK_RESOLVER = make_link_resolver(md_path, link_base)
    rev, tab, old = doc_model(doc)
    new = md_model(md_path)
    pairs = align(old, new)
    plan = Plan(tab)
    plan_structure(pairs, plan)
    if plan.groups:
        return rev, "structure", plan
    plan_text(pairs, plan)
    if plan.groups:
        return rev, "text", plan
    plan_style(pairs, plan)
    return rev, "style", plan


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan")
    p.add_argument("doc")
    p.add_argument("md")
    p.add_argument("-o", "--out")
    p.add_argument("--doc-id", default=None)
    p.add_argument("--link-base", default=None,
                   help="URL that relative links are resolved against, e.g. "
                        "https://github.com/OWNER/REPO/blob/main (paths are taken from the repository root)")
    p.add_argument("-v", "--verbose", action="store_true")
    m = sub.add_parser("model")
    m.add_argument("md")
    a = ap.parse_args()
    if a.cmd == "model":
        for e in md_model(a.md):
            print(e)
        return
    doc = json.load(open(a.doc))
    rev, phase, plan = plan_sync(doc, a.md, a.link_base)
    reqs = plan.requests()
    print("phase: %s, %d request(s)" % (phase, len(reqs)))
    notes = plan.notes if a.verbose else plan.notes[:40]
    for n in notes:
        print("  " + n)
    if len(plan.notes) > len(notes):
        print("  ... %d more" % (len(plan.notes) - len(notes)))
    if a.out:
        body = {"requests": reqs, "writeControl": {"requiredRevisionId": rev}}
        if a.doc_id:
            body["documentId"] = a.doc_id
        json.dump(body, open(a.out, "w"), ensure_ascii=False, indent=1)
        print("wrote %s" % a.out)


if __name__ == "__main__":
    main()

---
name: gdoc-sync
description: Bring a proposal's Google Doc copy up to date with the repo Markdown by minimal in-place edits that preserve review comments. Use when asked to update, sync or regenerate a Google Doc for the Puffin proposal or the expression analysis note.
---

# Sync a proposal's Google Doc

The Markdown in the repo is canonical. Each synced file has a Google Doc copy for review comments, owned by the author's personal Google account (not the work account), which is always updated in place and never recreated:

| Markdown | Google Doc id | Notes |
|---|---|---|
| `docs/proposals/puffin-blob-types-and-compaction.md` | `18qlGNPfPy5iT_aRgxLVCqh5W1LuINR7x8Eu2zC6HMoU` | Linked from the dev-list post |
| `docs/proposals/expression-analysis.md` | `1gmTCaXxCtguUwV9yGNRfISdcTE7E4u48P4BfQJ47La0` | Created 2026-11-20 |

`tools/gdoc_sync.py` plans the edits; the Google Docs connector applies them. Both connectors must be signed into that personal account. Before any write, check with Drive `get_file_metadata` that the doc's `parentId` is visible and `canMoveItemWithinDrive` is true; if the connector is on another account, stop and ask the user to reconnect it on claude.ai (Settings, Connectors), then run `/mcp`.

Loop until the plan is empty, with `<md>` and `<id>` from the table:

1. Read the doc with the Docs connector's `read_doc`. The result is too large for context and is saved to a file; use that path.
2. Plan:
   ```
   python3 -I tools/gdoc_sync.py plan <saved read_doc file> <md> \
     --link-base https://github.com/acruise/iceberg-indexing-ecosystem/blob/main \
     --doc-id <id> -o <scratchpad>/plan.json -v
   ```
   It reports the phase (structure, then text, then style) and what it will change. Read the notes before applying: a large deletion or an edit to a paragraph that carries a comment deserves a look.
3. Send `requests` and `writeControl` from the plan file to `update_doc` unchanged. The batch is guarded by the revision it was planned against, so a concurrent edit makes it fail cleanly; then go back to step 1.
4. Repeat from step 1. Done when the plan reports 0 requests.

Then check comments with Drive `read_file_content` and `includeComments: true`.

Known limits, which the tool reports or refuses rather than guesses at:

- New code blocks become 9pt Roboto Mono paragraphs, since the API cannot create the Docs code-block element.
- Nested bullets are created at the top level, with a note.
- A paragraph that continues a list item is unbulleted, as the importer renders it.
- Block quotes (the proposal's `> **TODO**` notes) become ordinary paragraphs; their bold lead-in carries the distinction.
- A new table whose preceding paragraph is also new waits a round, so it is inserted after the text phase has created that paragraph.
- A change in a table's column count is refused; adjust that table by hand.
- Blank separator paragraphs already in the doc are never deleted.

To check the tool after changing it, plan against the Markdown of the commit the doc was last synced from; it should report no text or structure changes.

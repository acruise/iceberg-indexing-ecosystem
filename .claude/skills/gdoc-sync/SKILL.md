---
name: gdoc-sync
description: Bring the proposal's Google Doc copy up to date with the repo Markdown by minimal in-place edits that preserve review comments. Use when asked to update, sync or regenerate the Google Doc.
---

# Sync the proposal's Google Doc

The Markdown in `docs/proposals/puffin-blob-types-and-compaction.md` is canonical. The Google Doc `18qlGNPfPy5iT_aRgxLVCqh5W1LuINR7x8Eu2zC6HMoU` is linked from the dev-list post, so it is always updated in place and never recreated. `tools/gdoc_sync.py` plans the edits; the Google Docs connector applies them.

Loop until the plan is empty:

1. Read the doc with the Docs connector's `read_doc`. The result is too large for context and is saved to a file; use that path.
2. Plan:
   ```
   python3 -I tools/gdoc_sync.py plan <saved read_doc file> docs/proposals/puffin-blob-types-and-compaction.md \
     --link-base https://github.com/acruise/iceberg-indexing-ecosystem/blob/main \
     --doc-id 18qlGNPfPy5iT_aRgxLVCqh5W1LuINR7x8Eu2zC6HMoU -o <scratchpad>/plan.json -v
   ```
   It reports the phase (structure, then text, then style) and what it will change. Read the notes before applying: a large deletion or an edit to a paragraph that carries a comment deserves a look.
3. Send `requests` and `writeControl` from the plan file to `update_doc` unchanged. The batch is guarded by the revision it was planned against, so a concurrent edit makes it fail cleanly; then go back to step 1.
4. Repeat from step 1. Done when the plan reports 0 requests.

Then check comments with Drive `read_file_content` and `includeComments: true`.

Known limits, which the tool reports or refuses rather than guesses at:

- New code blocks become 9pt Roboto Mono paragraphs, since the API cannot create the Docs code-block element.
- Nested bullets are created at the top level, with a note.
- A change in a table's column count is refused; adjust that table by hand.
- Blank separator paragraphs already in the doc are never deleted.

To check the tool after changing it, plan against the Markdown of the commit the doc was last synced from; it should report no text or structure changes.

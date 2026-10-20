# UI/UX session findings (2026-10-09)
Format: `N. [case] result — finding`
1. [create duplicate name] error reads "create workspace failed: 400" — backend message lost (workspaces.ts drops the body; providers.ts keeps it). Dialog closes and the typed name is lost.
2. [delete dialog] body repeats the title verbatim (Dialog falls back to <p>{title}</p>). Needs a real body line.
3. [delete active workspace] falls back to the first workspace (refresh picks ws[0]); the code comment promises the empty state. Deleting then typing sends to a different workspace.
4. [rename dialog] the field is not selected on open; typing appends to the old name. (low)
5. [first send] "…" placeholder for ~10 s while Ollama loads the model; no hint that the model is loading. (low; status says streaming…)
OK: create, rename, delete, Escape/backdrop close, Enter sends, Send→Stop, model label on the turn, thinking block.
6. [switch to a workspace with a run in progress] the joined turn has no model label (state frame carries no label); the label appears only after a reload.
7. [Stop] the partial reply shows no "stopped" marker; it reads like a complete answer.
8. [page reload] the active workspace resets to the first one; the user loses their place (and any hint that another workspace still runs). Persist the active id in localStorage.
9. [sidebar] no indicator for a workspace with a run in progress (user story: see running background tasks).
OK: switch away/back during a run replays and continues live; two workspaces run at once; Stop saves the partial reply; a reload keeps the run going.
10. [approval dialog] a click outside the dialog (e.g. on the sidebar to switch workspace) or Escape silently REJECTS the tool call; the workspace does not switch. Approve/Reject should live on the tool card (inline), not in a modal.
11. [empty finished answer] a stopped or failed turn with no text shows "…" (the loading placeholder) forever.
12. [model error] "404 Not Found from POST …/api/chat" shows in the global banner under the header, far from the turn; the turn itself shows "…". Show the error on the turn (like a saved row's error).
13. [Composer] App.send returns undefined on the normal path, so Composer's "keep the text until the promise resolves true" branch only runs for the compact-first prompt; the comment misleads. (low)
14. [status line] says "idle" during a Compact click (the chip + button say compacting). (low)
15. [compact-first dialog] text "Current context is bigger than context window. Should compact first. Proceed?" is awkward English.
16. [markdown] replies render as plain text: ``` fences, # headings, **bold** show literally. The biggest readability gap for users.
17. [tool cards] long tool results (file reads, grep) are not capped or collapsible; a big read floods the transcript. (medium)
OK: Fetch models + limits + save; usage bar window + "limit" chip + progress; limit validation (<65536); Compact → chip → summary line with tokens saved; model picker switch → turn label; unknown model → error + composer back.
18. [error banner] a run error stays in the top banner until the next send. (low; moot once run errors live on the turn)
19. [mobile 375px] the sidebar keeps 220px and the main pane overflows horizontally; no responsive layout.
20. [auto-scroll] scrolling up during a stream is overridden on every flush (review finding 19, still open).
21. [two tabs] a run started in tab 2 is invisible in tab 1 (idle, same workspace): the server pushes events but no state frame, and the client drops events of unknown runs. Tab 1 can also send and hit the busy error.
22. [composer] the message is sent untrimmed (leading spaces kept). (low)
23. [long workspace name] wraps over four lines in the sidebar. (low, acceptable)
24. [compact-first Yes with a broken last model] compaction fails (by design the last model compacts); the only sign is the top banner; the turn then runs over the window.
OK: provider form errors show inside the form with the backend text; Escape closes form then panel; light mode; whitespace-only send blocked; Shift+Enter newline + pre-wrap render; rapid workspace switching keeps one clean stream; stop from the sending tab; sub-agent chips expand.

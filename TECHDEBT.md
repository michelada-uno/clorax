# Tech debt

## Scroll model — replace the giant spacer div

Current viewport uses a "fake-scroll" spacer: one `<div id="space">` sized to the
logical sheet (`cols*CW × rows*RH`) so the native scrollbar reflects the sheet
size, with only the visible window of cells rendered on top.

**Problem:** the spacer height is bounded by the browser's max element pixel size
(~33.5M px Chrome/Safari, ~17.9M px Firefox). At 26px/row that caps usable rows
at ~1.28M (Chrome) / ~688k (Firefox). `MAX-ROWS` is pinned to 600000 to stay
under the Firefox limit. Scrollbar precision is also terrible at that scale
(~1 px ≈ thousands of rows).

**Want:** a scroll model that needs **no huge div** — compute everything from a
logical position instead of a physical one. Options:
- custom/synthetic scrollbar whose thumb maps to a row range (non-linear ok),
  decoupled from any sized element;
- "logical scroll": intercept wheel/drag, keep a virtual `r0/c0` offset, render
  the window at fixed screen coords (translate, not scroll), draw our own
  scrollbar. Address-box jump already proves far-navigation without scrolling.

This removes the row cap entirely and makes the cap purely a coordinate clamp.

## Other

- Concurrent edits can race into a transient `#ERR` / stale toast (per-sheet
  lock serializes server side, but simultaneous async posts arrive unordered).
- No config system yet — grid bounds, geometry are `def` constants. Fold into
  per-sheet settings once persistence lands.

## Sessions — sheet lifecycle + per-session position

`sheets*` loads sheets on demand but never unloads them; an execution context
lives forever once touched. Also viewport `view*`/`dims*` are global (one active
view), so two sessions on the same sheet fight over scroll position.

Once user sessions exist:
- ref-count sheets by active session; **unload** (close the execution context,
  drop from `sheets*`) when no session references a sheet. Save before unload.
- store **viewport position per session key**, not globally.
- hook **session start / close** to acquire/release sheet refs. Datastar's
  official SDK exposes connection lifecycle (SSE open/close) we can use to drive
  this — release on disconnect.

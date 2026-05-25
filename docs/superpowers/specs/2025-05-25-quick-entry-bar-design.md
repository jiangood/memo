# Quick Entry Bar — Design Spec

## Summary
Add a compact 2-line quick-entry editor at the top of the memo list page, enabling instant note capture without navigating to the full editor. A dedicated expand button opens the full `MemoEditScreen` with the current text.

## Motivation
Users want to capture thoughts quickly: open app, type immediately, done. The current flow (tap FAB → navigate to editor → type) adds friction for quick notes.

## Layout

```
┌──────────────────────────────────────┐
│  ↻ Sync   12 memos            ⚙️     │  TopAppBar
│            synced 2025-05-25         │
├──────────────────────────────────────┤
│  ┌────────────────────────────┬──┐   │
│  │ Quick note... (2 lines)    │ ⛶ │   │  ← QuickEntryBar
│  └────────────────────────────┴──┘   │
├──────────────────────────────────────┤
│  Memo item 1                  date   │
│  Memo item 2                  date   │
│  ...                                  │
└──────────────────────────────────────┘
```

## Component: `QuickEntryBar`

### Location
Inserted in `MemoListScreen` between the TopAppBar divider and the memo `LazyColumn`.

### Visual spec
- **Height**: Fixed at ~2 lines of text (approx 56dp with padding)
- **Shape**: Rounded rectangle (RoundedCornerShape)
- **Background**: `surfaceVariant` with slight alpha
- **Placeholder**: "Quick note..." in `onSurfaceVariant` color
- **Expand button**: `Icons.Default.OpenInFull` or equivalent fullscreen icon, positioned at the trailing edge

### Behavior
| Trigger | Action |
|---|---|
| Focus / tap field | Show keyboard, cursor appears |
| Text input | Update local state (not persisted until save) |
| Keyboard Done / Submit | Save as new memo → clear field → update list |
| Tap expand (⛶) icon | Navigate to `MemoEditScreen` with current text via `initialContent` param |
| Empty field + Done | No-op (not saved) |

### Data flow
1. User types in `QuickEntryBar` → local `TextFieldValue` state in `MemoListScreen`
2. On submit (Done): call `viewModel.createQuickNote(text)` → `MemoRepository.save()` → refresh list → clear input
3. On expand: call `onNavigateToEdit(null)` with initialContent carrying the typed text

### Changes required
- **MemoListScreen.kt**: Add `QuickEntryBar` composable, add `createQuickNote` to ViewModel interaction
- **MemoListViewModel.kt**: Add `createQuickNote(content: String)` function that saves a memo and refreshes the list
- **MemoEditScreen / NavGraph**: Already supports `initialContent` parameter — no changes needed

## Git / Sync
- Quick note is saved as a regular `.md` file in `pile/`
- No special sync treatment; next auto-sync will pick it up

## Constraints
- Min height carefully chosen to avoid accidental tap-through to list items below
- Expand button only shown when keyboard is visible (or always visible — TBD based on UX feel)
- If the user is in search mode (search query active), the QuickEntryBar could be hidden to avoid confusion

## Open questions (resolved during implementation)
- Q: Expand button always visible or only when focused?
  - A: Always visible, but subtly styled. Ensures discoverability.
- Q: Should QuickEntryBar replace or complement the FAB?
  - A: Complement. FAB remains for "create new blank memo."

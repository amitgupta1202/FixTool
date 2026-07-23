# Scenarios rail — Phase 3: favourites, sort, sections, collapse

**Status: SHIPPED (2026-07-23).**
- Phase 1+2 (list-weight fix, compact/expandable report, Current-run pin): commit `7c2c969`.
- `createdAt` foundation: commit `fb94b1e`.
- Sort + ★ favourites sections + collapse-all: commit `d668062`.

**One deviation from the plan below, deliberate:** `createdAt` is minted at **first `save()`**
(`previous == null && createdAt == null`), not at each named creation site. One mint point covers
new/capture/duplicate/remap, and — the property that matters — a pre-existing file is *provably* never
rewritten to add the key (an edit has `previous != null`, so it is never stamped). The clock is injected for
tests. This supersedes §1's "Stamp sites" table and §9's duplicate/remap note.

## Where this sits

The rail redesign is three phases, in priority order:

- **Phase 1 — the layout bug.** `ScenariosRail.kt:160` — the `LazyColumn` has no `.weight(1f)`, so it is measured against the full pane height, renders *below* the run report, and runs off the bottom edge with no way to scroll to it. Give it `.weight(1f)`. This is what makes the list reachable no matter how big an error is.
- **Phase 2 — tame the run report.** `RunStatusLine` (`ScenariosRail.kt:733-828`) is an unbounded, non-scrolling block above the list; its diagnosis loop (`:813-823`) and first-failure line (`:787-792`) have no line/count caps. Make it a collapsible, `heightIn(max = …)` + internal-scroll summary that can never exceed ~⅓ of the rail.

Neither phase touches disk or the data model. **This document is Phase 3** — the ergonomic layer (favourites, sort, sections, collapse-all) — which *does* touch the model and adds a local view-state file, so it carries the migration burden and is specified carefully below.

## Revision from the earlier proposal

The earlier sketch put **both** `favourite` and `createdAt` on the `Scenario` model. Given the (correct) caution about mutating already-released scenario files, this doc splits them:

- **`createdAt` → `Scenario` field.** It is intrinsic ("when was this authored"), needs to travel and be diffable, and is stamped **only at creation** — so it never rewrites a legacy file.
- **`favourite` → local view-state file** (a `Set<String>` of ids), **not** a `Scenario` field. Starring is a personal, weightless organizational act. Keeping it local means toggling a star **never rewrites the scenario file**, never stamps step-ids into a pre-id file as a side effect, and never shows up as git noise in a shared scenarios folder.

Trade-off: a favourite does not travel when you share/copy a scenario file. For a local, single-user tool that is the right default. If we later want team-shared "core" stars, promoting `favourite` to a `Scenario` field is a compatible follow-up (additive key, same rules as `createdAt` below).

Net model surface: **one** additive `Scenario` field (`createdAt`) and **one** new local file (`scenario_view.json`).

---

## 1. Data model: `Scenario.createdAt`

`model/scenario/Scenario.kt` — add, following the `SavedFixMessage.kt:12` / `FixConnectionProfile.kt:11` precedent but **nullable**:

```kotlin
/**
 * Epoch millis when this scenario was first authored. Null on every file written before this field
 * existed — those are NOT rewritten to add it; sort falls back to the file's mtime for them. Stamped
 * only at genuine creation (never on load, never on edit-save), so a legacy file stays byte-identical.
 */
val createdAt: Long? = null,
```

**Why nullable, not `= System.currentTimeMillis()`:** a live-clock default would stamp "created now" onto every pre-existing scenario the first time the new build reads it, collapsing the entire installed corpus to one instant and making "creation order" meaningless. Null = "unknown; approximate with file mtime."

### Stamp sites (creation only)

| Site | File:line | Action |
|---|---|---|
| Blank new (VM) | `FixMessageViewModel.kt:1368` | `Scenario(… , createdAt = System.currentTimeMillis())` |
| Blank new (rail) | `ScenariosRail.kt:291` | same |
| Capture | `ScenarioCapture.kt:393` | same (covers paste-capture if it funnels here — verify) |
| Duplicate | `FixMessageViewModel.kt:1551` | `.copy(id = …, name = …, createdAt = System.currentTimeMillis())` |
| Remap copy | `FixMessageViewModel.kt:1564` | same override |

**The `.copy()` gotcha:** both duplicate paths use `scenario.copy(id = …)`, which *carries the source's `createdAt` forward* unless overridden. A duplicate is created *now*, so it must override `createdAt` — otherwise a copy claims its parent's birth date and sorts wrong. Audit for any other `Scenario(` or `.copy(id =` that mints a new identity.

`fromJson` (`ScenarioCodec.kt:79`) is **load, not creation** — it reads the stored value or null; it must never stamp.

---

## 2. Codec: additive, version stays 1

`ScenarioCodec.kt` is hand-written and additive-by-construction (`fromJson` reads keys by name, ignores the rest), so both compatibility directions are already handled *provided we obey the version gate*.

**Write** (`toJson`, `:43-57`) — default-omitting, like `traffic`/`binding`:
```kotlin
scenario.createdAt?.let { put("createdAt", it) }
```

**Read** (`fromJson`, `:79-90`):
```kotlin
createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull,
```

**Do NOT bump `CURRENT_SCENARIO_VERSION` (`:41`, currently 1).** The gate at `:73-78` *refuses* any file whose `version` exceeds what the build understands (tested at `ScenarioCodecTest:385`). `createdAt` is additive and safe-to-ignore — a build that doesn't know it loses nothing about what the scenario checks. Bumping to 2 would make the **already-released app refuse to open any file the new build writes**, breaking cross-version sharing and rollback. The version bumps only for changes that would make old code *misread* a file (new step type, new match op) — this is not that. Precedent: `traffic`/`binding` were added as keys with no version bump.

---

## 3. Migration contract

| Direction | Outcome |
|---|---|
| Old file → new app | `createdAt` absent → null; sort uses file mtime. Loads unchanged. |
| New file → **released app** | Unknown key ignored (version still 1, so no refusal). Loads fine; drops `createdAt` only if it re-saves. |
| Legacy file, never edited | Stays null forever. Never rewritten on upgrade. |
| Legacy file, edited + saved | `createdAt` stays null (default-omitting write grows no key) → still byte-identical for that field. |

**Governing principle — zero-touch:** no launch-time migration pass, no bulk rewrite to backfill. New keys appear only on the user's next natural save of a scenario they actually changed.

---

## 4. Local view-state store: `scenario_view.json`

A new file at `~/.fixtool/scenario_view.json` (sibling of `app_settings.json`; **not** inside the scenarios dir, which is the shared/diffable corpus). It holds only local, regenerable view chrome — losing it costs nothing.

Unlike `Scenario`, this is a fresh local file with no control-surface contract, so use plain `@Serializable`:

```kotlin
@Serializable
data class ScenarioViewState(
    val sortMode: ScenarioSort = ScenarioSort.NAME,
    val favouriteIds: Set<String> = emptySet(),
    val collapsedSections: Set<String> = emptySet(),   // subset of {"favourites","all"}
    val expandedScenarioIds: Set<String> = emptySet(), // per-scenario step expansion; optional polish
)

enum class ScenarioSort { NAME, RECENTLY_MODIFIED, CREATED }
```

`ScenarioViewStateService` mirrors `AppSettingsService`, with two hard rules:

1. **Defensive load.** `Json { ignoreUnknownKeys = true }`; on `IOException`/`SerializationException`/missing file → return `ScenarioViewState()` defaults. It must **never** throw into the rail.
2. **Prune dangling ids on load.** `favouriteIds`/`expandedScenarioIds` are intersected with the live scenario id set, so a deleted scenario leaves no ghost. (This is the reason favourites-as-ids is safe here — no orphan can accumulate.)

**Explicitly not `AppSettings`.** None of these are settings; the `no setting is invisible` test (`SettingsPagesTest:28`) would demand a settings-page control for each. Keeping them out of `AppSettings` also avoids the settings-migration caveat noted at `AppSettings.kt:16-18`.

---

## 5. Sort

Three modes; sort applies **within each section independently**.

| Mode | Comparator (all with `.thenBy { name.lowercase() }.thenBy { id }` for total, frame-stable order) | Data |
|---|---|---|
| **Name** (default) | `compareBy { it.name.lowercase() }` | current behaviour |
| **Recently modified** | `compareByDescending { modifiedAt(it.id) ?: Long.MIN_VALUE }` | `ScenarioService.modifiedAt` — free today |
| **Creation order** (oldest first) | `compareBy { it.createdAt ?: modifiedAt(it.id) ?: Long.MAX_VALUE }` | new `createdAt`, mtime fallback |

Put it in a **pure, unit-testable helper**, not the composable and not the service:

```kotlin
data class RailView(val favourites: List<Scenario>, val others: List<Scenario>)

fun railView(
    scenarios: List<Scenario>,
    filter: String,
    sort: ScenarioSort,
    favouriteIds: Set<String>,
    modifiedAt: (String) -> Long?,
): RailView
```

`ScenarioService.list()` (`:52`) keeps its name-sort — other callers (control server, id-collision determinism at `:160`) rely on it. The rail sorts the derived view on top; disk order never changes.

**Picker UI:** a small sort control in `RailHeader` (`ScenariosRail.kt:386`), next to the filter — a `⇅ Name ▾` dropdown (Name / Recently modified / Creation order). Default **Name**. Selection persists to `sortMode`.

---

## 6. Favourites + sections

**Star behaviour** (favourite lives in `favouriteIds`, so a toggle writes only `scenario_view.json`):

- A favourited scenario appears **only** in the top **★ Favourites** section, removed from **All** (no duplicate rows).
- In **Favourites**: a filled ★ shows always; click = remove.
- In **All**: an outline ☆ appears **on hover** (joining the existing hover cluster, `ScenariosRail.kt:551-578`); click = add, and the row moves up to Favourites.

**Section rules:**

- Section headers (`★ Favourites` / `All`) render **only when `favourites` is non-empty**. With zero favourites the rail looks exactly as it does today — the feature is invisible until used, so nothing changes for someone who never stars anything.
- A header is clickable to collapse/expand its section; state persists in `collapsedSections`.
- The filter (`:96-107`) applies first; sections are computed on the filtered set.

**Interaction with auto-expand-on-failure** (`:89-92`): when a run fails, ensure the failing scenario's **section is expanded** (not just its steps), honouring the existing "a failure the author cannot see is a failure they won't fix" principle (`:88`).

---

## 7. Collapse-all / expand-all

The user's explicit ask. Acts on per-scenario **step** expansion (the ▸/▾ per row, today `expanded: Set<String>` held only in memory at `:80`):

- A chevron toggle in `RailHeader`: **Collapse all** clears the set; **Expand all** fills it with visible ids.
- Persist as `expandedScenarioIds` (pruned on load). Auto-expand-on-failure still fires and wins after a run; a manual Collapse-all wins after that.

(Section collapse in §6 is a separate, coarser control — whole sections vs. one scenario's steps. Collapse-all is the must-have; section-collapse is polish and can be severed if we want a smaller first cut.)

---

## 8. Tests

**Codec — extend `ScenarioCodecTest`** (guards the migration contract permanently):

1. a file with no `createdAt` round-trips **byte-identical** (grows no key) — extends `:33`/`:334`;
2. `createdAt = null` writes **nothing**;
3. a file **carrying** `createdAt` loads at `version = 1` — the "released app reads a new file" proof;
4. `createdAt` absent → the creation-order comparator falls back to `modifiedAt`, not to "now".

**View-state — new `ScenarioViewStateServiceTest`:**

- missing file → defaults; corrupt/partial JSON → defaults, no throw;
- `favouriteIds`/`expandedScenarioIds` pruned to live scenario ids on load;
- round-trips sortMode + favourites + collapsed sections.

**Sort/sections — new `RailViewTest`** (pure helper): each sort mode orders correctly and is total (ties broken by name then id); favourites split out and removed from `others`; legacy null `createdAt` sorts by mtime; empty favourites → empty top section.

**Guard — extend `SettingsPagesTest`:** assert `favourite`/`sortMode` are **not** `AppSettings` fields (they must stay in view-state), so a future refactor can't silently move them there and trip `no setting is invisible`.

**Duplicate/remap:** a duplicate and a remap copy each get a fresh `createdAt` (not the source's) and are **not** favourited.

---

## 9. Decisions & edge cases

- **Favourite location:** local view-state (§ revision), recommended over a `Scenario` field for zero file mutation. Promotable later if team-shared stars are wanted.
- **Duplicate/remap inherit nothing personal:** fresh `createdAt`, not favourited.
- **Empty favourites:** hide both headers; rail reads as today.
- **Filter hides all favourites:** `★ Favourites` header hidden for that filter.
- **Creation order = oldest first** ("the order I built the suite"). Newest-first is a one-line variant if preferred.

## 10. Out of scope (deferred)

- "Recently run / failing first" sort — needs run history beyond the single last-run the VM tracks today.
- Team-shared favourites (promote `favourite` to a `Scenario` field).
- User-defined groups/folders beyond the single Favourites split.

## 11. Touchpoints

| File | Change |
|---|---|
| `model/scenario/Scenario.kt` | `+ createdAt: Long? = null` |
| `service/ScenarioCodec.kt` | default-omitting write + read of `createdAt`; **version unchanged** |
| `model/ScenarioViewState.kt` *(new)* | view-state data class + `ScenarioSort` enum |
| `service/ScenarioViewStateService.kt` *(new)* | load/save `~/.fixtool/scenario_view.json`, defensive + prune |
| `ui/ScenariosRail.kt` | sort picker + collapse-all in `RailHeader`; sectioned list; star toggles; `railView` helper wiring; section-aware auto-expand |
| `ui/RailView.kt` *(new, or in ScenariosRail)* | pure `railView(...)` helper |
| `viewmodel/FixMessageViewModel.kt` | stamp `createdAt` at `:1368`, `:1551`, `:1564`; expose view-state flow + mutators |
| `service/ScenarioCapture.kt` | stamp `createdAt` at `:393` |
| tests | as §8 |
</content>
</invoke>

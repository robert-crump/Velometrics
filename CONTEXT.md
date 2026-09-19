# Velometrics domain glossary

Named concepts used throughout the codebase. Architecture-review skills are informed by this file — keep it tight and add terms as new concepts crystallize.

## PoiSelectionState

The value type describing the POI-layer selection on the Map View screen: which category chip is active and which single POI, if any, is popped up after a tap.

- **Lives in:** `ui/screens/mapview/PoiSelectionState.kt` (UI-screen state, not a domain entity).
- **Interface:** three intents — `selectChip(chip)`, `selectPoi(poiWD)`, `dismissPoi()` — each returns a new `PoiSelectionState`. Default value: `PoiSelectionState.None`.
- **Holds:** `activeChip: String?` (drives `showPoiLayer`/`visiblePois` filtering in `MapViewViewModel`) and `selected: PoiWithDistances?` (the popped-up POI, rendered by `PoiPopupCard`).
- **Independent axes:** re-selecting the active chip toggles it off and leaves `selected` untouched — switching categories does not dismiss whatever popup is currently open.
- **Raw POI data is separate:** the full POI list (`MapViewViewModel._allPois`) is a plain data flow, not part of this state — it isn't a "what is the user looking at" concern.
- **History:** this entry previously described the Navigation screen's `PoiSelectionState`, deleted in `9ac43df` along with the screen itself (`ecf2d80`). `MapViewViewModel` had since regressed to three loose `MutableStateFlow`s before this reinstatement (issue #199).

## SessionEnergy

The kcal and macronutrient totals derived from a `CyclingSession`'s fat and carb gram counts.

- **Lives in:** `domain/model/SessionEnergy.kt`
- **Interface:** `val CyclingSession.energy: SessionEnergy?` — returns `null` when the session has no power data (either gram value is missing).
- **Holds:** `totalKcal: Int` (rounded), `fatGrams: Double`, `carbGrams: Double`.
- **Formats its own display:** `formatTotalKcal()` ("1.340 kcal", German thousands separator), `formatFatCarbGrams()` ("42g / 187g").
- **Trust contract:** `fatBurnedGrams` and `carbsBurnedGrams` are already clamped ≥ 0 at the source (`GeoUtils.fatBurnKcalPerSec` / `carbBurnKcalPerSec`). Callers do not re-clamp.
- **Energy densities:** `CyclingConstants.KCAL_PER_GRAM_FAT` (9.3) and `KCAL_PER_GRAM_CARB` (4.1).

## RideLifecycle

The one owner of the obligations around adding or removing rides, so callers don't each re-implement them (#203).

- **Lives in:** `domain/service/RideLifecycle.kt` (interface) and `RideLifecycleImpl.kt`.
- **Interface:** `import(sources, onSmallFile): Flow<ImportProgress>`, `trackImports(reclusterMode) { ... }: ImportBatch`, `recluster()`, `delete(ids): DeleteResult`.
- **Import bracket:** `trackImports` captures the reveal baseline, runs the block, evaluates the reveal, and reclusters in a `finally`. Home's file picker (`import`) and `DropboxSyncWorker` both go through it. One shared mutex serializes batches across both, released before reclustering.
- **`ReclusterMode`:** `Background` (UI: launched on the application scope so the reveal isn't delayed) or `Await` (worker: finished inside the job).
- **Sources are lazy:** `ImportSource(fileName, read)`; `read` returning null is a per-file error and the batch continues. `UriImportSourceReader` is the thin Android adapter, so the module never sees a `Uri`.
- **Small files:** the `onSmallFile` suspend callback decides confirm/skip. Home bridges it to the dialog with a `CompletableDeferred`; Dropbox sync never asks and just counts them as skipped.
- **Delete:** deletes, then invalidates the Dropbox sync cursor (a failure there is logged, still `Deleted`). It deliberately never reclusters (#192: lazy refresh path).
- **Not covered:** the Repeated Routes / Repeated Intervals screens refresh their own clustering directly; MapView has no import/delete obligations.


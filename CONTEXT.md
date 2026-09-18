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

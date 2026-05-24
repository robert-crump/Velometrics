# Velometrics domain glossary

Named concepts used throughout the codebase. Architecture-review skills are informed by this file — keep it tight and add terms as new concepts crystallize.

## PoiSelectionState

The single value type describing what POI the user is currently looking at on the Navigation screen. Replaces four parallel `MutableStateFlow`s that were previously updated in lockstep.

- **Lives in:** `ui/screens/navigation/PoiSelectionState.kt` (UI-screen state, not a domain entity).
- **Interface:** four intents — `pickFromList(poiWD)`, `pickFromMap(poiWD)`, `dismiss()`, `consumeCameraMove()` — each returns a new `PoiSelectionState`. Default value: `PoiSelectionState.None`.
- **Holds:** `selected: Selected?` (the picked `Poi` plus its `PoiWithDistances` for the popup) and `pendingZoomTo: Poi?` (one-shot: signals the screen to ease the camera once, then call `consumePoiCameraMove`).
- **Origin matters:** list-pick sets `pendingZoomTo`; map-pick does not (the user is already looking at the map).
- **Tab coupling:** `selectedTab` is *not* part of this state. The "list-pick also flips to MAP tab" rule lives in `NavigationViewModel.pickPoiFromList`, not in the state machine — selection and tab are orthogonal axes.
- **Reset:** `setMode` / `resetMode` write `PoiSelectionState.None` directly; there is no explicit "reset" intent.

## SessionEnergy

The kcal and macronutrient totals derived from a `CyclingSession`'s fat and carb gram counts.

- **Lives in:** `domain/model/SessionEnergy.kt`
- **Interface:** `val CyclingSession.energy: SessionEnergy?` — returns `null` when the session has no power data (either gram value is missing).
- **Holds:** `totalKcal: Int` (rounded), `fatGrams: Double`, `carbGrams: Double`.
- **Formats its own display:** `formatTotalKcal()` ("1.340 kcal", German thousands separator), `formatFatCarbGrams()` ("42g / 187g").
- **Trust contract:** `fatBurnedGrams` and `carbsBurnedGrams` are already clamped ≥ 0 at the source (`GeoUtils.fatBurnKcalPerSec` / `carbBurnKcalPerSec`). Callers do not re-clamp.
- **Energy densities:** `CyclingConstants.KCAL_PER_GRAM_FAT` (9.3) and `KCAL_PER_GRAM_CARB` (4.1).

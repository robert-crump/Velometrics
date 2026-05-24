# Velometrics domain glossary

Named concepts used throughout the codebase. Architecture-review skills are informed by this file — keep it tight and add terms as new concepts crystallize.

## SessionEnergy

The kcal and macronutrient totals derived from a `CyclingSession`'s fat and carb gram counts.

- **Lives in:** `domain/model/SessionEnergy.kt`
- **Interface:** `val CyclingSession.energy: SessionEnergy?` — returns `null` when the session has no power data (either gram value is missing).
- **Holds:** `totalKcal: Int` (rounded), `fatGrams: Double`, `carbGrams: Double`.
- **Formats its own display:** `formatTotalKcal()` ("1.340 kcal", German thousands separator), `formatFatCarbGrams()` ("42g / 187g").
- **Trust contract:** `fatBurnedGrams` and `carbsBurnedGrams` are already clamped ≥ 0 at the source (`GeoUtils.fatBurnKcalPerSec` / `carbBurnKcalPerSec`). Callers do not re-clamp.
- **Energy densities:** `CyclingConstants.KCAL_PER_GRAM_FAT` (9.3) and `KCAL_PER_GRAM_CARB` (4.1).

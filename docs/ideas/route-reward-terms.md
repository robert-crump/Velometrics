# Deferred route-reward terms

Salvaged from the deleted `RewardComposer` (route composition now lives in Ride-Graph).

Deferred future terms (each is just another `+ w·term`):
- vista: DEM prominence, pure geometry — rewards high-vista edges
- surface: penalty for poor surface quality (column shipped in DB, not yet mapped to MapEdge)
- quiet: traffic exposure penalty
- wind: ± request-time tailwind on effortful legs; account for wind shelter vs open-field exposure

Rejected terms:
- scenery: too subjective, no measurable signal
- crowding: time-of-day dependent, no reliable data source in v1
- training-match: out of scope — ride-quality, not training-plan optimization

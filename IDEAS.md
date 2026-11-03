# Ideas and proposals (not shipped: K decides)

Coworkers: add bigger ideas here with what / why / risk / size. K: delete a line to say no, or reply in chat to say yes.

- **Map: draw the street layer once, not every frame.** What: cache roads/areas/buildings into a bitmap when the
  camera hasn't moved, redraw only the user/device/overlay layer per frame. Why: the map redraws at 20 fps even
  when nothing moves; on the emulator that saturates the CPU (90th-percentile frame 350-500 ms) and caused an
  ANR with the Search dialog open (worked around in v2.6.1 by resting behind dialogs); on the phone it costs
  battery and heat. Risk: medium (3D tilt + depth order, cache invalidation on zoom/tilt/new tiles); needs the
  emulator screenshots checked closely. Size: ~150 lines in MapView3D, one day on a branch.
- **Emulator test: the UI dump often fails ("could not get idle state" / "null root node") because of that
  same constant redraw**, so many steps run blind or are skipped (see summary.txt of any run). The caching above
  fixes the cause; meanwhile the script could press Back after the search step and use the cached button
  positions more. Small, test-only; no release needed. (Coworker can do this without a yes.)

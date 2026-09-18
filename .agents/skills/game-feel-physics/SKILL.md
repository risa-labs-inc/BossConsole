---
name: game-feel-physics
description: Enforces game feel, responsive animations, and physical tactile feedback in Compose UI games.
triggers:
  - "animation"
  - "drag"
  - "gesture"
  - "physics"
  - "game feel"
---
# Game Feel Principles
- Spring Dynamics: Never use linear interpolation for piece movement. Use 'spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)'.
- Constrained Axis Clamping: Clamp 1D drag offsets within valid collision intervals. Apply elastic drag resistance (0.3x multiplier) if dragging into a barrier.
- Move Snapping: Snap pieces to the nearest grid cell if moved beyond 40% of cell width; spring back to origin if released under 40%.
- Tactile Feedback: Trigger subtle scale deformation (e.g., 1.02x stretch along drag axis) while held.

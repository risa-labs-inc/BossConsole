---
name: puzzle-balancer
description: Validates level difficulty, solvability, and step requirements for deterministic puzzle games.
triggers:
  - "puzzle"
  - "level"
  - "generator"
  - "difficulty"
---
# Puzzle Balance Standards
- Minimum Path Verification: Every level must run through the BFS solver during instantiation to confirm ^* > 0$.
- Branching Factor Check: Prune trivial configurations where the exit path is unblocked in under 4 moves.
- Deadlock Safety: Ensure the board starts in an open, non-terminal state. Provide deterministic level presets (Beginner: 8-12 moves, Expert: 25+ moves).

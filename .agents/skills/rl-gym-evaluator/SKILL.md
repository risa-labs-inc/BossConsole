---
name: rl-gym-evaluator
description: Enforces Gym and Reinforcement Learning observation/action standards for AI agent games.
triggers:
  - "gym"
  - "eval"
  - "reinforcement learning"
  - "benchmark"
  - "trajectory"
---
# AI Gym Standards
- Observation Schema: Return clean JSON containing current grid coordinates, vehicle orientations, valid actions, and optimal steps remaining.
- Reward Function:
  - Goal reached: +1.0
  - Step penalty: -0.01 per step to penalize stalling
  - Invalid move attempted: -0.05
- Optimality Metrics: Calculate step efficiency $\eta = (d^* / \text{actual moves}) \times 100\%$. Track cycle counts (repeated board hashes).

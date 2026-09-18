# Boss Arcade: Rush Hour Gym

> **Tracks**: Track 01 (New Agent-Playable Games) & Track 04 (Benchmark & Evaluation)  
> **Repository**: `risa-labs-inc/BossConsole`  
> **Package**: `ai.rever.boss.arcade.rushhour`

---

## 1. Executive Summary

**Boss Arcade: Rush Hour Gym** is a deterministic spatial constraint sliding puzzle engine integrated directly into BossConsole. Designed both as an interactive desktop arcade experience for human operators and a high-performance, verifiable Gym environment for autonomous AI agents, it bridges spatial planning, search heuristics, and tool-augmented agent evaluation.

### Core Game Mechanics
- **Grid Dimensions**: 6x6 discrete grid ($0 \le \text{row}, \text{col} < 6$).
- **Target Vehicle (`X`)**: Length 2, horizontally aligned on row 2.
- **Exit Coordinate**: `(row: 2, col: 5)` located on the right edge of the board.
- **Obstacle Vehicles**: Length 2 (cars) or length 3 (trucks), constrained to slide strictly along their fixed orientation axis (horizontal along rows, vertical along columns).
- **Physics & Constraints**: Vehicles cannot rotate, jump, or overlap. No boundary breaches.
- **Victory Condition**: Target vehicle `X` occupies cell `(2, 5)`.

---

## 2. Architecture & Component Hierarchy

The engine follows a modular, reactive architecture across Compose Multiplatform layers:

```
composeApp/
??? src/commonMain/kotlin/ai/rever/boss/arcade/rushhour/
?   ??? model/
?   ?   ??? RushHourVehicle.kt         # Immutable vehicle model & cell projection
?   ?   ??? RushHourBoard.kt           # 6x6 grid state, hashing, and built-in levels
?   ?   ??? RushHourEngine.kt          # Deterministic state reducer & move validator
?   ?   ??? RushHourSolver.kt          # Optimal BFS graph search & deadlock detector
?   ?   ??? RushHourGameState.kt       # Thread-safe StateFlow controller with Main dispatch
?   ??? eval/
?   ?   ??? RushHourTrajectoryLogger.kt # Trajectory evaluation, cycle & efficiency metrics
?   ??? ui/
?       ??? RushHourScreen.kt          # Compose UI with BossConsole Operator design tokens
??? src/desktopMain/kotlin/ai/rever/boss/arcade/rushhour/mcp/
?   ??? RushHourMcpTools.kt            # MCP Perception & Action tool provider
??? src/desktopTest/kotlin/ai/rever/boss/arcade/rushhour/
    ??? RushHourEngineTest.kt          # Core engine mechanics, collisions & win condition
    ??? RushHourSolverTest.kt          # BFS correctness & optimal move count validation
    ??? RushHourMcpTest.kt             # MCP tool invocation & schema verification
```

---

## 3. Built-In Challenge Configurations

The gym includes four mathematically verified classic boards with increasing puzzle depth:

| Level | Name | Vehicles | Optimal Moves ($d^*$) | Description |
|---|---|---|---|---|
| **Level 1** | Beginner | 8 vehicles | **8 moves** | Classic entry challenge requiring coordinated vertical clearing to open row 2. |
| **Level 2** | Intermediate | 8 vehicles | **11 moves** | Multi-step truck maneuvering requiring backwards-forwards oscillation. |
| **Level 3** | Advanced | 10 vehicles | **12 moves** | Dense layout with cascading dependencies across columns 2, 3, and 4. |
| **Level 4** | Expert | 10 vehicles | **14 moves** | Deep strategic puzzle with tight spatial packing and deceptive cycles. |

---

## 4. MCP Tools: Perception & Action API

The environment exposes three Model Context Protocol (MCP) tools registered with `McpToolRegistryImpl`. They are available both with standard identifiers and with the `mcp__boss__` prefix.

### 4.1. `arcade_rushhour_state` (`mcp__boss__arcade_rushhour_state`)
Inspects the current 6x6 puzzle board, vehicle coordinates, legal moves, optimal distance remaining ($d^*$), and win status.

**Input Arguments**: None (`{}`)

**Response Payload**:
```json
{
  "gridSize": 6,
  "targetVehicle": "X",
  "exit": { "row": 2, "col": 5 },
  "currentLevel": 1,
  "stepsTaken": 0,
  "isSolved": false,
  "isDeadlocked": false,
  "optimalDistanceRemaining": 8,
  "boardHash": "X:2,1,2,H|A:0,0,2,V|B:0,4,3,V|C:1,5,2,V|D:3,2,2,V|E:4,0,2,H|F:4,4,2,H|G:5,1,2,H",
  "vehicles": [
    { "id": "X", "row": 2, "col": 1, "length": 2, "isHorizontal": true, "cells": [{"row":2,"col":1},{"row":2,"col":2}] },
    { "id": "A", "row": 0, "col": 0, "length": 2, "isHorizontal": false, "cells": [{"row":0,"col":0},{"row":1,"col":0}] },
    ...
  ],
  "validMoves": [
    { "vehicleId": "A", "steps": 1 },
    { "vehicleId": "A", "steps": 2 },
    { "vehicleId": "D", "steps": -1 }
  ]
}
```

---

### 4.2. `arcade_rushhour_move` (`mcp__boss__arcade_rushhour_move`)
Executes an atomic displacement of a designated vehicle along its fixed axis.

**Input Arguments**:
```json
{
  "vehicleId": "A",
  "steps": 2
}
```
- `vehicleId` *(string, required)*: Identifier of the vehicle to slide (e.g. `"X"`, `"A"`, `"B"`).
- `steps` *(integer, required)*: Number of cells to move.
  - For horizontal vehicles: Positive = Right (`+col`), Negative = Left (`-col`).
  - For vertical vehicles: Positive = Down (`+row`), Negative = Up (`-row`).

**Response Payload (Success)**:
```json
{
  "success": true,
  "vehicleId": "A",
  "steps": 2,
  "stepsTaken": 1,
  "optimalDistanceRemaining": 8,
  "isSolved": false,
  "isDeadlocked": false,
  "boardHash": "..."
}
```

**Response Payload (Blocked / Invalid Move)**:
```json
{
  "success": false,
  "error": "Cannot move vehicle A by 3 steps: movement path is blocked by vehicle or grid boundary."
}
```

---

### 4.3. `arcade_rushhour_reset` (`mcp__boss__arcade_rushhour_reset`)
Resets the board to a chosen level (1 to 4) and wipes the trajectory evaluation log.

**Input Arguments**:
```json
{
  "level": 1
}
```
- `level` *(integer, optional, default: 1)*: Difficulty level (1 to 4).

**Response Payload**:
```json
{
  "success": true,
  "level": 1,
  "optimalDistance": 8,
  "message": "Board successfully reset to Level 1. Optimal moves: 8."
}
```

---

## 5. Benchmark & Evaluation Metrics (Track 04)

`RushHourTrajectoryLogger` captures complete step-level telemetry throughout an agent's run:

### Telemetry Points per Step
- `stepIndex`: Sequential move number.
- `action`: Vehicle and directional displacement (e.g., `X:+1`, `B:-2`).
- `boardHash`: Canonical state representation string.
- `latencyMs`: Elapsed duration per decision cycle.
- `optimalRemaining`: Ground truth $d^*$ calculated via BFS at that exact state.

### Aggregate Evaluation Summary
Upon reaching the exit condition, the logger computes:
1. **Total Moves Taken ($N$)**: Total actions performed.
2. **Initial Optimal Moves ($d^*_0$)**: Ground truth shortest path from the initial state.
3. **Planning Optimality ($\eta$)**:
   $$\eta = \left(\frac{d^*_0}{N}\right) \times 100\%$$
   A perfect agent achieves $\eta = 100\%$. Suboptimal actions or exploration detours degrade $\eta$.
4. **Cycle Count ($C$)**: Number of times the agent revisited a previously visited board configuration. Measures loop detection and memory efficiency.

---

## 6. Compose UI & Apple HIG Overhaul

The graphical front-end ([RushHourScreen.kt](file:///composeApp/src/commonMain/kotlin/ai/rever/boss/arcade/rushhour/ui/RushHourScreen.kt)) is built with Apple Human Interface Guidelines (HIG) and the `apple-design-toolkit`:

### 6.1. Visual & Spatial Architecture
- **Dynamic Aspect-Ratio Container**: Built using `BoxWithConstraints` and `Modifier.aspectRatio(1f)` to dynamically scale the 6x6 arena to window bounds without ever clipping or overflowing.
- **Continuous Squircle Shell**: Board shell styled in `#121620` with `16.dp` continuous squircle corners, `1.dp` subtle border (`Color.White.copy(0.08f)`), and layered soft ambient depth shadow.
- **Tactile Vehicles**:
  - **Car X (Target)**: Apple Sunset Coral to Warm Amber gradient with embossed directional chevron (`➔`) pointing directly to the exit gate.
  - **Blocking Cars & Trucks**: Matte Indigo and Graphite styling with tactile groove indicators and segmented cab/trailer division.
- **Pulsing Exit Gate**: Row 2, Col 5 features an animated pulsing emerald glow (`#30D158`) with directional exit cue.
- **Top HUD Badges**: High-contrast Apple pill capsules displaying Moves, Optimal Distance ($d^*$), and Efficiency % ($\eta$), plus a segmented level selector (Levels 1–4).

### 6.2. 1D Constrained Drag & Snap Physics (`RushHourDragMath.kt`)
- **Axis Locking**: Horizontal vehicles strictly lock to X drag (zero Y offset); vertical vehicles strictly lock to Y drag (zero X offset).
- **Dynamic Boundary Clamping**: Real-time open space calculations (`minSteps`, `maxSteps`) clamp drag translation between `[minSteps * cellSize, maxSteps * cellSize]`.
- **Snap Threshold**: Dragging $\ge 40\%$ commits a move to `RushHourEngine`; dragging $< 40\%$ smoothly bounces back using spring physics (`animateFloatAsState`).
- **Accessible Click Controls**: Directional step buttons and keyboard shortcuts provided as alternatives.

### 6.3. Interactive Help Sheet (`RushHourHelpSheet.kt`)
- Accessible via the `(?)` button in the top HUD.
- Modal sheet detailing puzzle rules, 1D sliding controls, copyable MCP CLI snippets, and keyboard shortcuts:
  - `Esc`: Close help modal.
  - `R`: Reset current board.
  - `1` - `4`: Select difficulty levels 1 to 4.

---

## 7. Verification & Benchmark Test Suites

All 27 automated tests pass cleanly (100% pass rate):

```bash
# Safe Windows test runner (avoids Gradle daemon resource locks)
./gradlew :composeApp:desktopTest -x :composeApp:desktopProcessResources --no-daemon --tests "ai.rever.boss.arcade.rushhour.*"
```

### Test Coverage (27/27 Passing):
- **`RushHourInteractionTest` (5/5)**: Evaluates 1D axis isolation (zero perpendicular offset), boundary and collision clamping, snap threshold decisions, and rapid alternating gesture concurrency.
- **`RushHourEngineTest` (7/7)**: Validates boundary enforcement, collision detection, step sequences, and win detection.
- **`RushHourSolverTest` (6/6)**: Verifies BFS optimal path length against ground truth (L1=8, L2=11, L3=12, L4=14), plus deadlock detection.
- **`RushHourMcpTest` (6/6)**: Validates MCP registration, schema conformity, move dispatch, error reporting, and state resets.
- **`RushHourTabTest` (3/3)**: Confirms tab descriptor metadata, `needsNoInput = true`, and tab registry wiring.

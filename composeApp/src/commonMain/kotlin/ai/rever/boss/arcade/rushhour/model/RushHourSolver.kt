@file:Suppress("NestedBlockDepth", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")

package ai.rever.boss.arcade.rushhour.model

/**
 * Ground truth Breadth-First Search (BFS) solver and optimality evaluator for Rush Hour.
 */
object RushHourSolver {
    private data class SearchNode(
        val board: RushHourBoard,
        val distance: Int,
        val previousMove: ValidMove?,
        val parent: SearchNode?,
    )

    /**
     * Calculates the shortest optimal move count $d^*$ from the current board state to victory.
     *
     * @return Number of moves in the shortest solution path, 0 if already solved, or null if deadlocked/unsolvable.
     */
    fun findOptimalDistance(board: RushHourBoard): Int? {
        if (board.isSolved()) return 0

        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<RushHourBoard, Int>>()

        val initialHash = board.canonicalHash()
        visited.add(initialHash)
        queue.add(Pair(board, 0))

        while (queue.isNotEmpty()) {
            val (currentBoard, dist) = queue.removeFirst()

            if (currentBoard.isSolved()) {
                return dist
            }

            for (move in RushHourEngine.computeValidMoves(currentBoard)) {
                val nextBoardResult = RushHourEngine.move(currentBoard, move.vehicleId, move.steps)
                if (nextBoardResult.isSuccess) {
                    val nextBoard = nextBoardResult.getOrThrow()
                    val hash = nextBoard.canonicalHash()
                    if (visited.add(hash)) {
                        if (nextBoard.isSolved()) {
                            return dist + 1
                        }
                        queue.add(Pair(nextBoard, dist + 1))
                    }
                }
            }
        }

        return null
    }

    /**
     * Computes the complete optimal sequence of moves from the current board to victory.
     *
     * @return Ordered list of [ValidMove], empty list if already solved, or null if deadlocked.
     */
    fun findOptimalPath(board: RushHourBoard): List<ValidMove>? {
        if (board.isSolved()) return emptyList()

        val visited = HashSet<String>()
        val queue = ArrayDeque<SearchNode>()

        val rootNode = SearchNode(board, 0, null, null)
        visited.add(board.canonicalHash())
        queue.add(rootNode)

        var goalNode: SearchNode? = null

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()

            if (current.board.isSolved()) {
                goalNode = current
                break
            }

            for (move in RushHourEngine.computeValidMoves(current.board)) {
                val nextBoardResult = RushHourEngine.move(current.board, move.vehicleId, move.steps)
                if (nextBoardResult.isSuccess) {
                    val nextBoard = nextBoardResult.getOrThrow()
                    val hash = nextBoard.canonicalHash()
                    if (visited.add(hash)) {
                        val nextNode = SearchNode(nextBoard, current.distance + 1, move, current)
                        if (nextBoard.isSolved()) {
                            goalNode = nextNode
                            break
                        }
                        queue.add(nextNode)
                    }
                }
            }

            if (goalNode != null) break
        }

        if (goalNode == null) return null

        val path = mutableListOf<ValidMove>()
        var curr: SearchNode? = goalNode
        while (curr != null && curr.previousMove != null) {
            path.add(curr.previousMove)
            curr = curr.parent
        }
        path.reverse()
        return path
    }

    /**
     * Determines whether the current board has become mathematically deadlocked / unsolvable.
     */
    fun isDeadlocked(board: RushHourBoard): Boolean = findOptimalDistance(board) == null

    /**
     * Calculates the real-time Planning Optimality Score:
     * $$\text{Optimality } \eta = \left(\frac{d^*_0}{\text{Steps Taken}}\right) \times 100\%$$
     *
     * If steps taken is 0, returns 100.0% if solvable, or 0.0% if deadlocked.
     */
    fun calculateOptimalityScore(
        initialOptimalDistance: Int?,
        stepsTaken: Int,
    ): Double {
        if (initialOptimalDistance == null) return 0.0
        if (stepsTaken == 0) return 100.0
        val score = (initialOptimalDistance.toDouble() / stepsTaken.toDouble()) * 100.0
        return score.coerceIn(0.0, 100.0)
    }
}

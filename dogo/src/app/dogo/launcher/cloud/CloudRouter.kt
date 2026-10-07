package app.dogo.launcher.cloud

/**
 * Decides whether a query should be answered by the on-device model, or
 * escalated to a cloud provider.
 *
 * This is the heuristic fallback used when the on-device routing model
 * (FunctionGemma 270M) is unavailable or too unsure to be useful.
 *
 * Two signals drive the decision:
 *  - how confident the local model was in its own answer, and
 *  - how complex the request is.
 *
 * Complexity blends request length with the presence of "scope-expanding"
 * words such as *entire* or *automate*. A long request that asks for one
 * concrete change is still easy; a short request containing *everything* is
 * not.
 */
object CloudRouter {

    /** Below this, the local model is considered to not know the answer. */
    const val LOCAL_CONFIDENCE_THRESHOLD = 0.5f

    /** At or above this complexity score, a request goes to the cloud. */
    const val COMPLEXITY_THRESHOLD = 0.7f

    /** Words that signal the user is asking for something broad. */
    private val COMPLEX_KEYWORDS = setOf(
        "entire",
        "everything",
        "all",
        "optimal",
        "perfect",
        "complete",
        "comprehensive",
        "routine",
        "workflow",
        "automate",
        "integrate",
    )

    /** Words at which the length signal alone saturates. */
    private const val COMPLEX_LENGTH_WORDS = 20f

    /** Keyword hits at which the keyword signal alone saturates. */
    private const val COMPLEX_KEYWORD_HITS = 2f

    data class Decision(
        val shouldEscalate: Boolean,
        val reason: String,
        val complexityScore: Float,
    )

    fun shouldEscalate(query: String, localConfidence: Float): Decision {
        val complexity = computeComplexity(query)

        return when {
            localConfidence < LOCAL_CONFIDENCE_THRESHOLD -> Decision(
                shouldEscalate = true,
                reason = "On-device model was unsure (confidence $localConfidence)",
                complexityScore = complexity,
            )

            complexity >= COMPLEXITY_THRESHOLD -> Decision(
                shouldEscalate = true,
                reason = "Request is complex enough to be worth a stronger model",
                complexityScore = complexity,
            )

            else -> Decision(
                shouldEscalate = false,
                reason = "On-device model can handle this",
                complexityScore = complexity,
            )
        }
    }

    /**
     * Blends a length signal and a keyword signal into a 0..1 score.
     *
     * The keyword signal saturates at two hits: asking to set up "my entire
     * phone" is already an open-ended request, and piling on more
     * scope-expanding words should not change the verdict.
     */
    private fun computeComplexity(query: String): Float {
        val words = query.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val hits = COMPLEX_KEYWORDS.count { keyword ->
            query.contains(keyword, ignoreCase = true)
        }

        val lengthScore = (words / COMPLEX_LENGTH_WORDS).coerceIn(0f, 1f)
        val keywordScore = (hits / COMPLEX_KEYWORD_HITS).coerceIn(0f, 1f)

        return (lengthScore * 0.5f) + (keywordScore * 0.5f)
    }
}

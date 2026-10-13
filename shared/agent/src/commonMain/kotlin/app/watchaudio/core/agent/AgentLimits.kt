package app.watchaudio.core.agent

/**
 * Scope defaults and the amount limits of agent access (docs/research/agent-access.md §4.4): an
 * agent can page through what it is allowed to read, but never take everything in one go.
 */
object AgentLimits {
    /** A new pairing may read the segments of the last 30 days, text only. */
    const val DEFAULT_SCOPE_DAYS = 30

    const val DEFAULT_SEARCH_RESULTS = 20
    const val MAX_SEARCH_RESULTS = 50

    /** Utterances before and after the one asked for in `get_context`. */
    const val DEFAULT_CONTEXT = 5
    const val MAX_CONTEXT = 20

    /** Conversations listed by `get_day_timeline`. */
    const val MAX_TIMELINE_CONVERSATIONS = 100

    /** Text characters in one tool response (utterances beyond it are left out and counted). */
    const val MAX_RESPONSE_CHARS = 20_000

    /** One utterance's text is cut here. */
    const val MAX_UTTERANCE_CHARS = 500

    /** Characters of a conversation's opening in `get_day_timeline`. */
    const val OPENING_CHARS = 80

    const val MAX_QUERY_CHARS = 200

    /** Tool calls per pairing per minute. */
    const val CALLS_PER_MINUTE = 30

    /** Longest audio clip (`get_audio_clip`), padding included. */
    const val MAX_CLIP_MS = 30_000L

    /** Audio before and after the utterance in a clip. */
    const val CLIP_PADDING_MS = 1_000L

    /** A pause longer than this starts a new conversation in `get_day_timeline`. */
    const val CONVERSATION_GAP_MS = 5 * 60_000L
}

/** Sliding one-minute window of tool calls for one pairing. */
class RateLimiter(private val perMinute: Int = AgentLimits.CALLS_PER_MINUTE) {
    private val calls = ArrayDeque<Long>()

    /** Counts a call at [now] (epoch ms) and @return true, or false if the window is full. */
    fun tryAcquire(now: Long): Boolean {
        while (calls.isNotEmpty() && calls.first() <= now - WINDOW_MS) calls.removeFirst()
        if (calls.size >= perMinute) return false
        calls.addLast(now)
        return true
    }

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}

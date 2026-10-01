package app.navelo.server

/** Tracks requested scans independently from library changes, including scans that find no changes. */
internal class ScanRequests {
    data class Status(val generation: Long = 0, val completed: Long = 0, val error: String? = null)
    data class Reservation(val generation: Long, val start: Boolean)
    private var state = Status()

    @Synchronized fun reserve(): Reservation {
        if (state.generation > state.completed) return Reservation(state.generation, false)
        val next = state.generation + 1
        state = state.copy(generation = next, error = null)
        return Reservation(next, true)
    }

    @Synchronized fun complete(generation: Long, error: String?) {
        if (state.generation != generation || state.completed >= generation) return
        state = Status(generation, generation, error)
    }

    @Synchronized fun status(): Status = state
}

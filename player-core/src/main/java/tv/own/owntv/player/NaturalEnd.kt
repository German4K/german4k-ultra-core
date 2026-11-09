package tv.own.owntv.player

/**
 * Was nach dem natürlichen Ende eines Items passiert. Eigene Funktion, damit sie ohne Player testbar ist.
 *
 * German4K 3.1: ein *verkettetes* Item (Titel eines virtuellen 24/7-Senders) meldet sein Ende immer —
 * unabhängig von „Automatisch weiter" und einem weggeklickten Countdown: der Sender läuft weiter, genau
 * wie ein echter Sender nicht fragt, ob die nächste Sendung kommen darf.
 */
internal object NaturalEnd {
    enum class Action { STOP, NEXT, QUEUE_ENDED, ARCHIVE_ENDED, CHAINED_ENDED }

    fun decide(
        autoPlayNext: Boolean,
        autoNextCancelled: Boolean,
        queueSize: Int,
        queueIndex: Int,
        archive: Boolean,
        chained: Boolean,
    ): Action = when {
        chained && queueSize == 0 -> Action.CHAINED_ENDED
        !autoPlayNext || autoNextCancelled -> Action.STOP
        queueSize == 0 -> if (archive) Action.ARCHIVE_ENDED else Action.STOP
        queueIndex < queueSize - 1 -> Action.NEXT
        else -> Action.QUEUE_ENDED
    }
}

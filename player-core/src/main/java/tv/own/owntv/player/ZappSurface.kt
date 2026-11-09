package tv.own.owntv.player

/** German4K 3.2.1 (A5): Entscheidung „Surface nach dem Zappen neu bauen?“ — rein, damit testbar. */
internal object ZappSurface {
    fun neuBauen(vorher: Pair<Int, Int>?, jetzt: Pair<Int, Int>, istKachel: Boolean, schalterAn: Boolean): Boolean =
        schalterAn && !istKachel && vorher != null && vorher != jetzt
}

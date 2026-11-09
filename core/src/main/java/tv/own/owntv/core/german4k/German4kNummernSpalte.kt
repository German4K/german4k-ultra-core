package tv.own.owntv.core.german4k

/**
 * German4K 3.2.1 (U53): Eine M3U ohne `tvg-chno` hat keine Sendernummern — die feste Nummernspalte blieb dann als
 * leere Lücke zwischen Logo und Name stehen (final-a9/80-fav-live.png). Die Spalte gibt es nur, wenn die Einstellung
 * an ist UND mindestens ein geladener Sender eine Nummer trägt (gemischte Listen bleiben bündig).
 */
object German4kNummernSpalte {
    fun zeigen(
        einstellung: Boolean,
        nummern: Iterable<Int?>,
        an: Boolean = German4kFeatures.an(German4kSchalter.NUMMER_NUR_WENN),
    ): Boolean = einstellung && (!an || nummern.any { it != null })
}

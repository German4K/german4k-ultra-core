package tv.own.owntv.core.german4k

import java.time.Instant

/** Vertrags-Beispiel §3.3 (docs/superpowers/specs/2026-10-04-virtuelle-24-7-sender.md), Plan lückenlos. */
internal object VirtuellTestDaten {
    val SERVER: Instant = Instant.parse("2026-10-04T18:00:00.000Z")
    const val VERTRAG = """
    {"ok":true,"vertrag":1,"server_time":"2026-10-04T18:00:00.000Z","stufe":"team",
     "sender":[{"stream_id":13750001,"slug":"himym","name":"How I Met Your Mother 24/7","kurzname":"HIMYM",
       "kategorie":"🇩🇪 24/7 German4K · Serien: Sitcom","category_id":4711,"nummer":28001,"logo":null,
       "epg_id":"g4k.v.himym","virtuell":true,
       "jetzt":{"start":"2026-10-04T17:48:10.000Z","ende":"2026-10-04T18:10:12.000Z","dauer_sek":1322,"position_sek":710,
         "art":"series","ziel_id":880001,"titel":"HIMYM · S3E4 · Der Rauswurf","serie":"How I Met Your Mother",
         "staffel":3,"folge":4,"folgentitel":"Der Rauswurf","url":"https://german4k.tv/series/u/p/880001.mkv"},
       "plan":[
        {"start":"2026-10-04T17:48:10.000Z","ende":"2026-10-04T18:10:12.000Z","dauer_sek":1322,"art":"series","ziel_id":880001,
         "titel":"HIMYM · S3E4 · Der Rauswurf","serie":"How I Met Your Mother","staffel":3,"folge":4,"folgentitel":"Der Rauswurf",
         "url":"https://german4k.tv/series/u/p/880001.mkv"},
        {"start":"2026-10-04T18:10:12.000Z","ende":"2026-10-04T18:32:00.000Z","dauer_sek":1308,"art":"series","ziel_id":880002,
         "titel":"HIMYM · S3E5 · Wie ich Marshall traf","serie":"How I Met Your Mother","staffel":3,"folge":5,"folgentitel":"Wie ich Marshall traf",
         "url":"https://german4k.tv/series/u/p/880002.mkv"},
        {"start":"2026-10-04T18:32:00.000Z","ende":"2026-10-04T20:30:00.000Z","dauer_sek":7080,"art":"movie","ziel_id":555,
         "titel":"Ein Film (1999)","serie":null,"staffel":null,"folge":null,"folgentitel":null,
         "url":"https://german4k.tv/movie/u/p/555.mp4"}
       ]}]}
    """
    fun sender() = German4kVirtuellAntwort.parse(VERTRAG)!!.sender.single()
}

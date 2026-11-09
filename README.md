# German 4K Ultra Core – Quellcode

Player- und Kernbausteine der App **German 4K Ultra** (Version 3.3, Build 45) für Android TV,
veröffentlicht gemäß der **GNU General Public License v3** (siehe `LICENSE`).

## Grundlage

Basiert auf **OwnTV_Core** von Ashiq Hasan und den OwnTV-Mitwirkenden (Original-Beschreibung: `README-OwnTV.md`).
Die App selbst liegt in [`German4K/german4k-ultra`](https://github.com/German4K/german4k-ultra).

## Änderungen (GPLv3 § 5)

Dieses Werk wurde von German 4K verändert, zuletzt im November 2026 (Stand der ausgelieferten Version 3.3/45):
Anpassungen an Wiedergabe (libmpv/ExoPlayer), Quellen-Anbindung, EPG, Ausfallsicherheit und Diagnose.
Die Änderungen im Einzelnen ergeben sich aus dem Vergleich mit OwnTV_Core und aus `CHANGELOG.md`.

## Fremdbibliotheken

Enthält bzw. bindet u. a. **FFmpeg 7.1.1** (LGPL/GPL, Quelle: https://ffmpeg.org) und **AndroidX Media3 1.11.1**
(Apache 2.0) ein. Die zugehörigen Lizenzhinweise liegen in den jeweiligen Modulen.

## Bauen

`./gradlew build` (JDK 21, Android SDK). Wird als Modul von `german4k-ultra` eingebunden.

## Kontakt

https://german4k.com

package com.debridone.cloud.testing

import java.nio.file.Paths

/**
 * Runs the two stand-in providers on this computer, for trying DebridOne by hand without real
 * accounts:
 *
 * ```
 * java -cp <test fixtures classpath> com.debridone.cloud.testing.StandInMainKt 8791 8792 [folder of samples]
 * DEBRIDONE_REAL_DEBRID_API=http://127.0.0.1:8791 DEBRIDONE_TORBOX_API=http://127.0.0.1:8792 ./scripts/debridone/dev.sh
 * ```
 *
 * A file `sample.<extension>` in the folder of samples is served for every stand-in file of that
 * extension (so that a player or a reader has something real to open); other files are made-up
 * bytes. Allow the Real-Debrid code with `curl http://127.0.0.1:8791/__approve`; the TorBox key is
 * [StandInTorBox.API_KEY]. `curl "http://127.0.0.1:8792/__slow?ms=300"` makes that server hand files
 * out slowly (per 16 KiB), to try pausing and cancelling a download; `ms=0` ends it.
 */
fun main(arguments: Array<String>) {
    val samples = arguments.getOrNull(2)?.let { Paths.get(it) }
    val realDebrid = StandInRealDebrid(samples, arguments.getOrNull(0)?.toIntOrNull() ?: 8791)
    val torBox = StandInTorBox(samples, arguments.getOrNull(1)?.toIntOrNull() ?: 8792)
    println("Stand-in Real-Debrid: ${realDebrid.origin}  (allow the code: curl ${realDebrid.origin}/__approve; API token: ${StandInRealDebrid.PRIVATE_TOKEN})")
    println("Stand-in TorBox:      ${torBox.origin}  (API key: ${StandInTorBox.API_KEY})")
    Thread.currentThread().join()
}

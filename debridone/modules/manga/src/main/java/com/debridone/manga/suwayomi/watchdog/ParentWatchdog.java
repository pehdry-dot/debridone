package com.debridone.manga.suwayomi.watchdog;

import java.io.InputStream;

/**
 * Loaded into the Suwayomi-Server process that DebridOne starts ({@code -javaagent}). It waits on
 * the process's standard input, which is a pipe held open by DebridOne; when DebridOne ends for
 * any reason (quit, crash, kill), the pipe closes and this ends the server too, running its
 * shutdown hooks so that its database is closed properly. Without it a crash of DebridOne would
 * leave a server running that nobody stops.
 *
 * Plain Java with no dependencies: it runs inside another program's JVM.
 */
public final class ParentWatchdog {
    private ParentWatchdog() {
    }

    public static void premain(String arguments) {
        Thread thread = new Thread(ParentWatchdog::watch, "debridone-parent-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private static void watch() {
        InputStream input = System.in;
        byte[] buffer = new byte[256];
        try {
            while (input.read(buffer) >= 0) {
                // DebridOne writes nothing; anything read is ignored.
            }
        } catch (Throwable ignored) {
            // A broken pipe means the same as its end.
        }
        System.exit(0);
    }
}

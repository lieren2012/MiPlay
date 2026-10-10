package org.carplaytocarlife.iccoa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A bounded in-memory log shared by the service and the UI. No Android APIs. */
public final class Logs {

    private static final int CAPACITY = 400;
    private static final Deque<String> LINES = new ArrayDeque<>();

    private Logs() {
    }

    public static void add(String message) {
        synchronized (LINES) {
            LINES.addLast(message);
            while (LINES.size() > CAPACITY) {
                LINES.removeFirst();
            }
        }
    }

    public static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    /** Hex dump helper shared by the protocol code and the service. */
    public static String hex(byte[] data, int length) {
        StringBuilder out = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(String.format("%02x", data[i] & 0xFF));
        }
        return out.toString();
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}

package com.paytmmoney.seats.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class Ids {
    private Ids() {}

    public static String newId(int len) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, len);
    }

    public static String nowIso() {
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now());
    }

    /** Stable hash of (show + sorted seats) to detect same-key-different-body. */
    public static String bodyHash(String showId, List<String> sortedSeats) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(showId.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            for (String s : sortedSeats) {
                md.update(s.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

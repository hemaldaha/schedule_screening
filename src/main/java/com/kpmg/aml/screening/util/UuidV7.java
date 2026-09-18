/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 *
 * @author user
 */
/**
 * Generates RFC 9562 UUIDv7 — time-ordered, no external dependency required.
 *
 * Use this to generate primary keys for screening_exceptions rows.
 * UUIDv7 is time-ordered (sortable by insertion time) and avoids the
 * random-page-scatter hotspot of UUIDv4 on clustered B-tree indexes.
 */
public final class UuidV7 {

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * Returns a new UUIDv7 string.
     *
     * Layout (128 bits):
     *   [0..47]   unix_ts_ms  — 48-bit millisecond timestamp (sortable)
     *   [48..51]  version     — 0x7
     *   [52..63]  rand_a      — 12 random bits
     *   [64..65]  variant     — 0b10
     *   [66..127] rand_b      — 62 random bits
     */
    public static String generate() {
        long ms  = System.currentTimeMillis();
        long msb = (ms << 16) | 0x7000L | (RNG.nextLong() & 0x0FFFL);
        long lsb = (RNG.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }

    private UuidV7() {}
}
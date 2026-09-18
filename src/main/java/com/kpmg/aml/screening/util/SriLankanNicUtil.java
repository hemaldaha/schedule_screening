/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.util;

import java.time.Year;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author user
 */
@Slf4j
public class SriLankanNicUtil {

    private static final Pattern OLD_PATTERN = Pattern.compile("^\\d{9}[VX]$");
    private static final Pattern NEW_PATTERN = Pattern.compile("^\\d{12}$");
    private static final int CURRENT_YEAR    = Year.now().getValue();

    public enum NicStatus {
        VALID_OLD,
        VALID_NEW,
        UNUSABLE
    }

    public record NicPair(String oldNic, String newNic) {
        public boolean isUsable() {
            return oldNic != null || newNic != null;
        }
    }

    // ── Primary Entry Point ───────────────────────────────────────

    /**
     * Cleanse, validate, detect format and generate both NIC formats.
     * Returns NicPair(null, null) if NIC is unusable.
     */
    public static NicPair resolve(String rawNic) {
        if (rawNic == null || rawNic.isBlank()) {
            log.debug("[NicUtil] NIC is null or empty");
            return new NicPair(null, null);
        }

        String cleaned = auditAndClean(rawNic);
        if (cleaned == null) {
            log.debug("[NicUtil] NIC contains invalid characters: {}", rawNic);
            return new NicPair(null, null);
        }

        NicStatus status = detect(cleaned);

        return switch (status) {
            case VALID_OLD -> new NicPair(cleaned, toNewFormat(cleaned));
            case VALID_NEW -> new NicPair(toOldFormat(cleaned), cleaned);
            case UNUSABLE  -> new NicPair(null, null);
        };
    }

    // ── Format Detection ──────────────────────────────────────────

    private static NicStatus detect(String cleaned) {
        int length = cleaned.length();

        if (length == 10) {
            if (!OLD_PATTERN.matcher(cleaned).matches()) {
                log.warn("[NicUtil] Invalid old NIC format: {}", cleaned);
                return NicStatus.UNUSABLE;
            }
            return validateLogic(
                1900 + Integer.parseInt(cleaned.substring(0, 2)),
                Integer.parseInt(cleaned.substring(2, 5)),
                cleaned
            ) ? NicStatus.VALID_OLD : NicStatus.UNUSABLE;

        } else if (length == 12) {
            if (!NEW_PATTERN.matcher(cleaned).matches()) {
                log.warn("[NicUtil] Invalid new NIC format: {}", cleaned);
                return NicStatus.UNUSABLE;
            }
            return validateLogic(
                Integer.parseInt(cleaned.substring(0, 4)),
                Integer.parseInt(cleaned.substring(4, 7)),
                cleaned
            ) ? NicStatus.VALID_NEW : NicStatus.UNUSABLE;

        } else {
            log.warn("[NicUtil] Unexpected NIC length={} value={}", length, cleaned);
            return NicStatus.UNUSABLE;
        }
    }

    // ── Validation Logic ──────────────────────────────────────────

    private static boolean validateLogic(int year, int dayValue, String nic) {
        if (year > CURRENT_YEAR) {
            log.warn("[NicUtil] Future birth year={} nic={}", year, nic);
            return false;
        }

        int actualDay = dayValue;
        if (dayValue > 500) {
            actualDay = dayValue - 500;
        } else if (dayValue > 366) {
            log.warn("[NicUtil] Day value in dead zone={} nic={}", dayValue, nic);
            return false;
        }

        if (actualDay < 1) {
            log.warn("[NicUtil] Invalid day value={} nic={}", actualDay, nic);
            return false;
        }

        boolean isLeap = (year % 400 == 0) || ((year % 4 == 0) && (year % 100 != 0));
        int maxDays    = isLeap ? 366 : 365;

        if (actualDay > maxDays) {
            log.warn("[NicUtil] Day={} exceeds max={} for year={} nic={}", 
                actualDay, maxDays, year, nic);
            return false;
        }

        return true;
    }

    // ── Format Conversion ─────────────────────────────────────────

    /**
     * Old (10 char) → New (12 char)
     * [YY][DDD][SSSS][V/X] → [19YY][DDD][0][SSS]
     */
    private static String toNewFormat(String cleanedOld) {
        return "19"
            + cleanedOld.substring(0, 2)
            + cleanedOld.substring(2, 5)
            + "0"
            + cleanedOld.substring(5, 9);
    }

    /**
     * New (12 char) → Old (10 char)
     * Only valid for 19xx birth years.
     * Returns null for 20xx — no old format exists.
     */
    private static String toOldFormat(String cleanedNew) {
        if (!cleanedNew.startsWith("19")) {
            log.debug("[NicUtil] No old format for 20xx NIC: {}", cleanedNew);
            return null;
        }
        return cleanedNew.substring(2, 4)
            + cleanedNew.substring(4, 7)
            + cleanedNew.substring(8, 12)
            + "V";
    }

    // ── Cleansing ─────────────────────────────────────────────────

    /**
     * Single-pass cleanse.
     * Strips benign whitespace, rejects garbage characters.
     */
    private static String auditAndClean(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c <= 32)        continue;
            if (isDigit(c))     { sb.append(c); continue; }
            if (isVOrX(c))      { sb.append(Character.toUpperCase(c)); continue; }
            return null;
        }
        return sb.toString();
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isVOrX(char c)  {
        return c == 'V' || c == 'v' || c == 'X' || c == 'x';
    }
}



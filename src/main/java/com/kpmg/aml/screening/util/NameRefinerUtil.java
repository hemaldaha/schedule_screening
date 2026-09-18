/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.util;

/**
 *
 * @author user
 */
public class NameRefinerUtil {

    /**
     * Refines names by removing all non-alphanumeric "garbage" chars, invisible
     * ASCII control codes, and normalizing whitespace.
     */
    public static String refineName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        // 1. Clean characters and normalize case
        String cleaned = name
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .toUpperCase()
                .trim();

        // 2. Remove Solr Boolean operators (AND, OR, NOT) as whole words
        // \b ensures we don't accidentally remove names like "Orlando" or "Anthony"
        return cleaned.replaceAll("\\b(AND|OR|NOT)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
    
}

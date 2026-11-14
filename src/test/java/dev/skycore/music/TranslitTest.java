package dev.skycore.music;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TranslitTest {
    @Test
    void cyrillicBecomesLatin() {
        assertEquals("Gruppa krovi", Translit.toLatin("Группа крови"));
        assertEquals("Mumiy Troll", Translit.toLatin("Мумий Тролль"));
        assertEquals("Shchuka", Translit.toLatin("Щука"));
        assertEquals("Funk 2000", Translit.toLatin("Funk 2000"));
    }

    @Test
    void cyrillicSearchesAlsoRunInLatin() {
        assertEquals(List.of("Кино"), Translit.variants("Кино").subList(0, 1));
        assertEquals(List.of("Кино", "Kino"), Translit.variants("Кино"));
        assertEquals(List.of("funk"), Translit.variants(" funk "));
    }

    @Test
    void latinTypingIsAlsoSearchedAsCyrillicUnlessItIsAGenre() {
        assertEquals("тону", Translit.toCyrillic("tonu"));
        assertEquals("шанс", Translit.toCyrillic("shans"));
        assertEquals(List.of("tonu", "тону"), Translit.variants("tonu"));
        assertEquals(List.of("funk"), Translit.variants("funk"));
        assertEquals(List.of("ab"), Translit.variants("ab"), "too short to guess");
    }

    @Test
    void fallbackWordsAreTheLongestOnesAndOnlyForPhrases() {
        assertEquals(List.of(), Translit.words("Кино"));
        List<String> w = Translit.words("Кино Группа крови");
        assertTrue(w.contains("группа") && w.contains("gruppa") && w.contains("крови"));
        assertFalse(w.contains("кино") && w.size() > 6);
    }
}

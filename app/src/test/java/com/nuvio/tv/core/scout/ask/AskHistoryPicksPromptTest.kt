package com.nuvio.tv.core.scout.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskHistoryPicksPromptTest {
    @Test
    fun filmsPromptAsksForFilmsInTheSchemaShapeAndTheViewersLanguage() {
        val prompt = buildHistoryPicksPrompt(listOf("Heat 1995 (movie)", "Ronin 1998 (movie)"), series = false, answerLanguage = "Arabic")
        assertTrue(prompt.system.contains("\"rowName\""))
        assertTrue(prompt.system.contains("films they have not watched"))
        assertTrue(prompt.system.contains("type is always movie"))
        assertTrue(prompt.system.contains("written in Arabic"))
        assertEquals("Watched lately:\nHeat 1995 (movie)\nRonin 1998 (movie)", prompt.user)
    }

    @Test
    fun seriesPromptAsksForSeries() {
        val prompt = buildHistoryPicksPrompt(listOf("Dark (series)", "Lost (series)"), series = true, answerLanguage = "English")
        assertTrue(prompt.system.contains("TV series they have not watched"))
        assertTrue(prompt.system.contains("type is always series"))
        assertFalse(prompt.system.contains("type is always movie"))
    }

    @Test
    fun historyIsCappedAtTwentyFiveLinesAndFifteenHundredCharacters() {
        val many = (1..60).map { "A rather long title number $it 2001 (movie)" }
        val prompt = buildHistoryPicksPrompt(many, series = false, answerLanguage = "English")
        val lines = prompt.user.removePrefix("Watched lately:\n").lines()
        assertTrue(lines.size <= HISTORY_PICKS_MAX_LINES)
        assertTrue(prompt.user.length <= "Watched lately:\n".length + 1500)
    }
}

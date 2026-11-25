package com.vasik96.spojeuk

import org.junit.Assert.assertEquals
import org.junit.Test

/** prettyName s předkompilovaným regexem a rychlou cestou musí dávat totéž co původní implementace. */
class PrettyNameTest {
    private fun reference(name: String): String = name.replace(Regex(",(?=\\S)"), ", ").replace("null", "").trim()

    @Test fun sameAsOriginal() {
        val samples = listOf(
            "", " ", "Ústí nad Labem,Stříbrníky", "Ústí nad Labem, Stříbrníky", "Děčín,,hl.n.", "A,B,C",
            "null", "Praha,null", " Most,nádraží ", "Teplice, lázně", "konec,", ",začátek", "Liberec,\tx",
            "nullnull,a", "Most", "Chomutov,žel.st.", "a ,b", "x,\ny",
        )
        for (s in samples) assertEquals("[$s]", reference(s), prettyName(s))
    }
}

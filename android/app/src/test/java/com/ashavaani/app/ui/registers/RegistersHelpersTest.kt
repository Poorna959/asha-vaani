package com.ashavaani.app.ui.registers

import com.ashavaani.app.model.toDisplayString
import org.junit.Assert.assertEquals
import org.junit.Test

class RegistersHelpersTest {

    @Test
    fun `infers ANC, NEWBORN, and IMMUNISATION categories from register names`() {
        assertEquals("NEWBORN", RegisterFieldHelpers.inferRegisterCategory("Newborn HBNC Register"))
        assertEquals("IMMUNISATION", RegisterFieldHelpers.inferRegisterCategory("Ward 4 Vaccination Camp"))
        assertEquals("ANC", RegisterFieldHelpers.inferRegisterCategory("ANC First Intake Register"))
        assertEquals("ANC", RegisterFieldHelpers.inferRegisterCategory("Village Maternal Survey"))
    }

    @Test
    fun `parses typed manual answers for NUMBER, BOOLEAN, and TEXT fields`() {
        assertEquals("28", RegisterFieldHelpers.parseTypedAnswer("28", "NUMBER").toDisplayString())
        assertEquals("2.8", RegisterFieldHelpers.parseTypedAnswer("2.8", "NUMBER").toDisplayString())
        assertEquals("Yes", RegisterFieldHelpers.parseTypedAnswer("haan", "BOOLEAN").toDisplayString())
        assertEquals("No", RegisterFieldHelpers.parseTypedAnswer("no", "BOOLEAN").toDisplayString())
        assertEquals("O+", RegisterFieldHelpers.parseTypedAnswer(" O+ ", "TEXT").toDisplayString())
    }

    @Test
    fun `deduplicates and orders custom register fields case-insensitively`() {
        val ordered = RegisterFieldHelpers.buildOrderedFields(
            listOf(
                PresetClinicalField("Age", "NUMBER"),
                PresetClinicalField("Blood Group", "TEXT"),
                PresetClinicalField("age", "TEXT"), // duplicate ignored
                PresetClinicalField("  ", "TEXT"), // blank ignored
                PresetClinicalField("House / Ward", "TEXT"),
            )
        )
        assertEquals(3, ordered.size)
        assertEquals("Age", ordered[0].fieldName)
        assertEquals(0, ordered[0].orderIndex)
        assertEquals("Blood Group", ordered[1].fieldName)
        assertEquals(1, ordered[1].orderIndex)
        assertEquals("House / Ward", ordered[2].fieldName)
        assertEquals(2, ordered[2].orderIndex)
    }

    @Test
    fun `builds FileProvider authority matching AndroidManifest`() {
        assertEquals(
            "com.ashavaani.app.fileprovider",
            RegisterFieldHelpers.fileProviderAuthority("com.ashavaani.app"),
        )
    }
}

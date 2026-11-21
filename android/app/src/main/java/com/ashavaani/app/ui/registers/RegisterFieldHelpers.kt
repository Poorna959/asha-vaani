package com.ashavaani.app.ui.registers

import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.RegisterFieldCreate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

data class PresetClinicalField(
    val name: String,
    val type: String,
)

/**
 * Pure helper utilities for Custom Registers (FR-7.1 – FR-7.6):
 * clinical preset chips, typed answer parsing, category inference, and FileProvider authority.
 */
object RegisterFieldHelpers {

    val PRESET_FIELDS: List<PresetClinicalField> = listOf(
        PresetClinicalField("Age", "NUMBER"),
        PresetClinicalField("Blood Group", "TEXT"),
        PresetClinicalField("LMP", "DATE"),
        PresetClinicalField("Parity", "TEXT"),
        PresetClinicalField("Medical History", "TEXT"),
        PresetClinicalField("Phone", "TEXT"),
        PresetClinicalField("House / Ward", "TEXT"),
        PresetClinicalField("Birth Weight", "NUMBER"),
    )

    val FIELD_TYPES: List<String> = listOf("TEXT", "NUMBER", "DATE", "BOOLEAN")
    val PATIENT_CATEGORIES: List<String> = listOf("ANC", "NEWBORN", "IMMUNISATION")

    fun inferRegisterCategory(registerName: String): String {
        val lower = registerName.lowercase()
        return when {
            lower.contains("newborn") || lower.contains("hbnc") || lower.contains("baby") -> "NEWBORN"
            lower.contains("immun") || lower.contains("vaccin") || lower.contains("tika") -> "IMMUNISATION"
            else -> "ANC"
        }
    }

    /**
     * Convert a manual text entry into a strongly typed JSON primitive matching the field definition.
     */
    fun parseTypedAnswer(rawValue: String, fieldType: String): JsonElement {
        val trimmed = rawValue.trim()
        return when (fieldType.uppercase()) {
            "NUMBER" -> {
                trimmed.toLongOrNull()?.let { JsonPrimitive(it) }
                    ?: trimmed.toDoubleOrNull()?.let { JsonPrimitive(it) }
                    ?: JsonPrimitive(trimmed)
            }
            "BOOLEAN" -> {
                when (trimmed.lowercase()) {
                    "true", "yes", "y", "1", "haan" -> JsonPrimitive(true)
                    "false", "no", "n", "0", "nahi" -> JsonPrimitive(false)
                    else -> JsonPrimitive(trimmed)
                }
            }
            else -> JsonPrimitive(trimmed)
        }
    }

    /**
     * Build ordered, deduplicated [RegisterFieldCreate] list preserving user order.
     */
    fun buildOrderedFields(selected: List<PresetClinicalField>): List<RegisterFieldCreate> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<RegisterFieldCreate>()
        selected.forEach { item ->
            val cleanName = item.name.trim()
            if (cleanName.isNotEmpty() && seen.add(cleanName.lowercase())) {
                result.add(
                    RegisterFieldCreate(
                        fieldName = cleanName,
                        fieldType = item.type.uppercase(),
                        orderIndex = result.size,
                    )
                )
            }
        }
        return result
    }

    fun fileProviderAuthority(packageName: String): String = "$packageName.fileprovider"

    private val NAME_PUNCT_REGEX = Regex("[^a-z0-9\\u0900-\\u0d7f\\s]+", RegexOption.IGNORE_CASE)
    private val DOUBLE_LETTER_REGEX = Regex("([a-z])\\1+")
    private val PHONETIC_DIGRAPH_SUBS: List<Pair<String, String>> = listOf(
        "chh" to "ch",
        "th" to "t",
        "dh" to "d",
        "bh" to "b",
        "ph" to "p",
        "kh" to "k",
        "gh" to "g",
        "sh" to "s",
        "ee" to "i",
        "ii" to "i",
        "oo" to "u",
        "uu" to "u",
        "aa" to "a",
    )

    /**
     * Phonetic Indic-English name key matching backend `normalize_patient_name_key`
     * (e.g. "Savitha Rao" == "Savita Rao", "Suneetha Devi" == "Sunita Devi").
     */
    fun normalizePatientNameKey(name: String): String {
        val cleaned = NAME_PUNCT_REGEX.replace(name.trim().lowercase(), " ")
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.joinToString(" ") { raw ->
            var t = raw
            for ((src, dst) in PHONETIC_DIGRAPH_SUBS) {
                t = t.replace(src, dst)
            }
            t = DOUBLE_LETTER_REGEX.replace(t, "$1")
            if (t.endsWith("y") && t.length > 2) {
                t = t.dropLast(1) + "i"
            }
            t
        }
    }

    /**
     * Find an existing patient in the register matching [candidateName] phonetically.
     */
    fun findMatchingPatient(
        candidateName: String,
        patients: List<PatientRecord>,
    ): PatientRecord? {
        val key = normalizePatientNameKey(candidateName)
        if (key.isEmpty()) return null
        return patients.firstOrNull { normalizePatientNameKey(it.patientName) == key }
    }
}

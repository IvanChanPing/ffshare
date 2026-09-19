package com.caydey.ffshare

import android.content.Intent
import android.os.Bundle
import androidx.preference.*

/**
 * Purpose: owns the Settings screen, including the visible Automatic compression entry.
 * Invocation: user opens Settings and taps “Automatic compression”.
 * Contract: the row launches the SAF-backed AutoCompressSettingsActivity; folder monitoring is
 * enabled or disabled by that screen, not by this fragment.
 * Verification: XML/source wiring read back; real UI click path is UNVERIFIED this turn.
 */
class PreferencesFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        dynamicallyShowCustomName()
        dynamicallyAddCustomParamTooltips()
        findPreference<Preference>("pref_automatic_compression")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), autocompress.AutoCompressSettingsActivity::class.java))
            true
        }
    }
    private fun dynamicallyAddCustomParamTooltips() {
        val customParamKeys = arrayOf("pref_custom_video_params", "pref_custom_audio_params", "pref_custom_image_params")
        for (customParamKey in customParamKeys) {
            val element = findPreference<EditTextPreference>(customParamKey)
            element?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
    }
    private fun dynamicallyShowCustomName() {
        // only show pref_compressed_media_custom_name if pref_compressed_media_name is "Custom"
        val customMediaNamePreference = findPreference<EditTextPreference>("pref_compressed_media_custom_name")
        val compressedMediaNamePreference = findPreference<ListPreference>("pref_compressed_media_name")
        compressedMediaNamePreference?.setOnPreferenceChangeListener { _, value ->
            customMediaNamePreference?.isVisible = (value == "CUSTOM")
            true
        }
        // trigger update for initial load
        compressedMediaNamePreference?.callChangeListener(compressedMediaNamePreference.value)
    }
}

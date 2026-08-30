package cc.machado.audioblackbox.permissions

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class SharedPrefsOnboardingPreferencesTest {

    class FakeSharedPreferences(private val map: MutableMap<String, Any>) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    class FakeEditor(private val map: MutableMap<String, Any>) : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { staged[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        
        override fun commit(): Boolean {
            if (clear) map.clear()
            for ((k, v) in staged) {
                if (v == null) map.remove(k) else map[k] = v
            }
            staged.clear()
            clear = false
            return true
        }
        override fun apply() { commit() }
    }

    @Test
    fun `legacy seen_legal_notice key is cleanly removed without implying consent`() {
        val map = mutableMapOf<String, Any>("seen_legal_notice" to true)
        val prefs = FakeSharedPreferences(map)
        
        val context = mock<Context> {
            on { getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE) } doReturn prefs
        }
        
        val onboardingPrefs = SharedPrefsOnboardingPreferences(context)
        
        // Assert user still needs consent (default value is 0 when no consent key is present)
        assertEquals(0, onboardingPrefs.consentVersionAccepted)
        
        val input = PermissionResolverInput(
            recordAudioStatus = PermissionStatus.GRANTED,
            postNotificationsStatus = PermissionStatus.GRANTED,
            apiLevel = 34,
            isIgnoringBatteryOptimizations = true,
            hasAcceptedCurrentConsent = onboardingPrefs.consentVersionAccepted >= CURRENT_CONSENT_VERSION,
            hasRequestedRecordAudio = onboardingPrefs.hasRequestedRecordAudio,
            hasRequestedPostNotifications = onboardingPrefs.hasRequestedPostNotifications,
            hasSkippedBatteryOptimization = onboardingPrefs.hasSkippedBatteryOptimization
        )
        val nextStep = PermissionResolver.resolveNextStep(input)
        assertEquals(OnboardingStep.CONSENT, nextStep)
        
        // Assert the legacy key is cleanly removed from the underlying SharedPreferences
        assertFalse("Legacy key should be removed", prefs.contains("seen_legal_notice"))
    }
}

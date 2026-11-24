package com.sovereignops.sms

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sovereignops.sms.core.rules.WorkspaceMode
import org.fossify.commons.extensions.toast
import org.fossify.messages.R

/**
 * Quick Cover: one tap forces the inbox to Personal and holds Professional and Quarantine
 * notifications. Leaving it asks for biometrics or the device screen lock. Nothing is
 * deleted or moved; turning it off shows everything again.
 */
object SovereignCover {
    fun isOn(context: Context) = Sovereign.store(context).coverActive

    fun turnOn(context: Context) {
        val store = Sovereign.store(context)
        store.coverActive = true
        store.workspaceMode = WorkspaceMode.PERSONAL
        SovereignNotifications.clearHidden(context)
        refreshTile(context)
        context.toast(R.string.sv_cover_on)
    }

    /** Re-authenticate, then leave cover and call [done]. Without a device lock it just leaves. */
    fun turnOff(activity: FragmentActivity, done: () -> Unit = {}) {
        val auth = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val leave = {
            Sovereign.store(activity).coverActive = false
            refreshTile(activity)
            activity.toast(R.string.sv_cover_off)
            done()
        }
        if (BiometricManager.from(activity).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            leave()
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = leave()
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.sv_cover_reauth))
                .setAllowedAuthenticators(auth)
                .build()
        )
    }

    fun refreshTile(context: Context) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, QuickCoverTile::class.java)) }
    }
}

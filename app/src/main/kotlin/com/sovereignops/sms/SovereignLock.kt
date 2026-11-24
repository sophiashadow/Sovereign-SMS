package com.sovereignops.sms

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig

/**
 * Wires Sovereign settings onto upstream's app lock (no second lock screen):
 *  - the chosen [com.sovereignops.sms.core.lock.LockTimeout] becomes upstream's unlock timeout;
 *  - Immediate and Screen-off modes lock when the screen turns off;
 *  - FLAG_SECURE on every activity when "Block screenshots" is on.
 * Registered once from App.onCreate.
 */
object SovereignLock : Application.ActivityLifecycleCallbacks {
    @Volatile private var registered = false

    fun init(app: Application) {
        if (registered) return
        registered = true
        apply(app)
        app.registerActivityLifecycleCallbacks(this)
        // ACTION_SCREEN_OFF is only delivered to receivers registered at runtime.
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                val store = Sovereign.store(context)
                if (context.baseConfig.isAppPasswordProtectionOn && store.lockTimeout.locksOnScreenOff) {
                    context.appLockManager.lock()
                }
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    /** Push the stored timeout into upstream's lock; call after changing it. */
    fun apply(context: Context) {
        context.baseConfig.unlockTimeoutDurationMs = Sovereign.store(context).lockTimeout.upstreamTimeoutMs
    }

    fun secure(activity: Activity) {
        if (Sovereign.store(activity).secureScreen) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = secure(activity)
    override fun onActivityResumed(activity: Activity) = secure(activity)
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

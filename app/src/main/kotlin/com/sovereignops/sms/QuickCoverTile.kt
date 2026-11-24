package com.sovereignops.sms

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.fossify.messages.activities.MainActivity

/**
 * Quick Settings tile for Quick Cover. Turning cover on needs no authentication;
 * turning it off opens the app, which asks for biometrics or the screen lock.
 */
class QuickCoverTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = if (SovereignCover.isOn(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (!SovereignCover.isOn(this)) {
            SovereignCover.turnOn(this)
            onStartListening()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_LEAVE_COVER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    companion object {
        const val EXTRA_LEAVE_COVER = "sovereign_leave_cover"
    }
}

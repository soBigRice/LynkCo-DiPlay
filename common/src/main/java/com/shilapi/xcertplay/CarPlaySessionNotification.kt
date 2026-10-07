package com.shilapi.xcertplay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R

/** Reuses the connection notification; the media token lets System UI find our existing session. */
internal object CarPlaySessionNotification {
    const val CHANNEL = "diplay_connection"
    const val ACTION_MEDIA = "com.shihab.diplay.MEDIA_CONTROL"
    const val EXTRA_MEDIA_INDEX = "media_index"

    fun build(context: Context, epoch: Long, media: CarPlayMediaKeys.NotificationState?): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(context, 0, Intent(context, CarPlayHostActivity::class.java), flags)
        val stop = PendingIntent.getService(context, 1, Intent(context, DiPlaySessionService::class.java)
            .setAction(DiPlaySessionService.ACTION_STOP).putExtra(DiPlaySessionService.EXTRA_RUNTIME_EPOCH, epoch), flags)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(media?.title ?: context.getString(R.string.app_name))
            .setContentText(media?.artist ?: "CarPlay connection running")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        if (media != null) {
            fun action(index: Int, icon: Int, label: Int) {
                // Distinct identities keep an old session's immutable button from acquiring a new epoch.
                val intent = Intent(context, DiPlaySessionService::class.java).setAction(ACTION_MEDIA)
                    .setData(Uri.parse("diplay://media/$epoch/$index"))
                    .putExtra(DiPlaySessionService.EXTRA_RUNTIME_EPOCH, epoch).putExtra(EXTRA_MEDIA_INDEX, index)
                builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, icon),
                    context.getString(label), PendingIntent.getService(context, index + 10, intent, flags)).build())
            }
            action(CarPlayMediaButton.PREVIOUS, android.R.drawable.ic_media_previous, R.string.carplay_media_previous)
            action(if (media.playing) CarPlayMediaButton.PAUSE else CarPlayMediaButton.PLAY,
                if (media.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (media.playing) R.string.carplay_media_pause else R.string.carplay_media_play)
            action(CarPlayMediaButton.NEXT, android.R.drawable.ic_media_next, R.string.carplay_media_next)
            builder.setStyle(Notification.MediaStyle().setMediaSession(media.token).setShowActionsInCompactView(0, 1, 2))
                .setColorized(false)
        }
        return builder.addAction(Notification.Action.Builder(
            if (media != null) Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel) else null,
            "Disconnect", stop).build()).build()
    }
}

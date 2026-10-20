package com.mauricio.vista.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mauricio.vista.MainActivity
import com.mauricio.vista.data.Video

/**
 * Controles del video en la notificación y en la pantalla de bloqueo (pausa, siguiente, adelantar).
 * Mientras suena, Android no cierra la app: se puede escuchar con la pantalla apagada.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        Media.session?.let { runCatching { addSession(it) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = Media.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si se cierra la app desde recientes y no está sonando, se apaga todo
        val p = Media.session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }
}

/** Puente entre la pantalla del reproductor y la notificación. */
@UnstableApi
object Media {
    var session: MediaSession? = null
        private set

    /** Lo que se está viendo (para el título y la imagen de la notificación). */
    var current: Video? = null
    var onNext: (() -> Unit)? = null
    var hasNext: () -> Boolean = { false }

    fun attach(ctx: Context, player: ExoPlayer) {
        release(ctx)
        val wrapped = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().apply {
                    if (hasNext()) { add(Player.COMMAND_SEEK_TO_NEXT); add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
                }.build()

            override fun isCommandAvailable(command: Int): Boolean =
                if (command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) hasNext() else super.isCommandAvailable(command)

            override fun seekToNext() { if (hasNext()) onNext?.invoke() else super.seekToNext() }
            override fun seekToNextMediaItem() { if (hasNext()) onNext?.invoke() else super.seekToNextMediaItem() }

            override fun getMediaMetadata(): MediaMetadata {
                val v = current ?: return super.getMediaMetadata()
                return MediaMetadata.Builder()
                    .setTitle(v.title)
                    .setArtist(v.channel)
                    .setArtworkUri(Uri.parse(v.thumbHq))
                    .build()
            }
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = runCatching {
            MediaSession.Builder(ctx, wrapped).setId("vista").setSessionActivity(open).build()
        }.getOrNull()
        if (session != null) runCatching {
            val i = Intent(ctx, PlaybackService::class.java)
            ctx.startService(i)
        }
    }

    fun release(ctx: Context) {
        session?.let { s -> runCatching { s.release() } }
        session = null
        current = null
        onNext = null
        hasNext = { false }
        runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
    }
}

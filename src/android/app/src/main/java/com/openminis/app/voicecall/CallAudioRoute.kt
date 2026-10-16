package com.openminis.app.voicecall

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * 通话出声路径：扬声器（默认）或听筒。
 * 听筒要走 VoIP 路由，所以通话期间把系统切到 MODE_IN_COMMUNICATION，并指定内置听筒；
 * 切回扬声器或通话结束时必须 [release]，否则别的 App 的音量/路由会被留在通话模式。
 * 蓝牙/有线耳机在场时系统路由优先，这里不去抢。
 */
internal class CallAudioRoute(context: Context) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var engaged = false
    private var savedMode = AudioManager.MODE_NORMAL

    fun useEarpiece() = runCatching { engageEarpiece() }

    private fun engageEarpiece() {
        if (!engaged) {
            savedMode = audio.mode
            engaged = true
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val earpiece = audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (earpiece != null) audio.setCommunicationDevice(earpiece)
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
    }

    fun release() = runCatching { restore() }

    private fun restore() {
        if (!engaged) return
        engaged = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = savedMode
    }
}

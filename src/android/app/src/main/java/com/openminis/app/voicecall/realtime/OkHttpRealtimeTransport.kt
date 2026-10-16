package com.openminis.app.voicecall.realtime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** OkHttp WebSocket 传输。读超时关闭（长连接靠 ping 保活）。 */
class OkHttpRealtimeTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : RealtimeTransport {
    @Volatile private var socket: WebSocket? = null

    override fun connect(url: String, headers: Map<String, String>, listener: RealtimeTransport.Listener) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSE, null)
                listener.onClosed(reason.ifBlank { "code $code" })
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 不把请求头（含 API key）带进错误信息，只给状态码和异常类型。
                listener.onFailure(response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName))
            }
        })
    }

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    override fun close() {
        socket?.close(NORMAL_CLOSE, null)
        socket = null
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
    }
}

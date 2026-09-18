package com.openminis.app.voicecall.bloub

import com.openminis.app.voicecall.model.BloubState

/** Bloub 的视觉/文案映射：状态 -> SVG 文件名、状态文案、字幕。 */
data class BloubVisual(val file: String, val statusText: String, val subtitle: String)

object BloubStateMapper {

    fun file(s: BloubState): String = when (s) {
        BloubState.IDLE -> "neutre"
        BloubState.LISTENING -> "attentif"
        BloubState.THINKING -> "curieux"
        BloubState.SPEAKING -> "excite"
        BloubState.MUTED -> "somnolent"
        BloubState.CONFUSED -> "confus"
        BloubState.SUCCESS -> "fier"
        BloubState.ERROR -> "colere"
    }

    fun status(s: BloubState): String = when (s) {
        BloubState.IDLE -> "待机"
        BloubState.LISTENING -> "我在听"
        BloubState.THINKING -> "思考中…"
        BloubState.SPEAKING -> "正在说"
        BloubState.MUTED -> "已静音"
        BloubState.CONFUSED -> "没听清"
        BloubState.SUCCESS -> "搞定啦"
        BloubState.ERROR -> "出错了"
    }

    fun subtitle(s: BloubState): String = when (s) {
        BloubState.IDLE -> "我准备好啦，随时可以开始。"
        BloubState.LISTENING -> "好的，我已经打开你的日程，今天下午 3 点有一个产品评审会。"
        BloubState.THINKING -> "让我想想，你下午的会议和取快递的时间好像有点冲突。"
        BloubState.SPEAKING -> "已经帮你把会议改到 3 点半，快递也改成晚上 8 点取了。"
        BloubState.MUTED -> "我在待机，点麦克风继续聊。"
        BloubState.CONFUSED -> "抱歉没听清，你是想把会议改到几点？"
        BloubState.SUCCESS -> "全部安排好了，还有别的需要吗？"
        BloubState.ERROR -> "哎呀，这一步没成功，我重新试一下。"
    }

    fun visual(s: BloubState): BloubVisual = BloubVisual(file(s), status(s), subtitle(s))
}

package com.arashiaz.arivoice

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Runs a real GGUF model locally. No tool calls: generated text never executes phone actions. */
class OfflineChat(context: Context) {
    private val appContext = context.applicationContext
    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(appContext) }

    fun reply(prompt: String, language: SpeechLanguage): String = runBlocking {
        val state = withTimeout(30_000) {
            engine.state.first {
                it is InferenceEngine.State.Initialized || it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error
            }
        }
        if (state is InferenceEngine.State.Error) engine.cleanUp()
        if (engine.state.value is InferenceEngine.State.Initialized) {
            engine.loadModel(ChatModel.file(appContext).absolutePath)
            engine.setSystemPrompt(
                if (language == SpeechLanguage.PERSIAN)
                    "تو آری‌ویس هستی. به فارسی کوتاه و درست پاسخ بده. به دستگاه دسترسی نداری و ادعا نکن کاری را انجام داده‌ای."
                else "You are Ari-Voice. Answer briefly and accurately. You cannot control the device; never claim you performed an action."
            )
        }
        engine.sendUserPrompt(prompt, 160).toList().joinToString("").trim().also {
            check(it.isNotBlank()) { "مدل پاسخی تولید نکرد" }
        }
    }

    fun unload() {
        if (engine.state.value is InferenceEngine.State.ModelReady || engine.state.value is InferenceEngine.State.Error) {
            engine.cleanUp()
        }
    }
}

package com.arashiaz.arivoice

/** Static model files only; no command, audio or prompt is sent to these hosts. */
object ModelCatalog {
    const val PERSIAN_STT = "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip"
    const val ENGLISH_STT = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    // Official Qwen GGUF repository, pinned revision. License: Apache 2.0.
    const val CHAT = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val CHAT_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
}

package com.phonebuddy.app

object LlamaBridge {
    interface TokenCallback {
        fun onToken(piece: String)
    }

    init {
        System.loadLibrary("omp")
        System.loadLibrary("ggml-base")
        System.loadLibrary("ggml-cpu")
        System.loadLibrary("ggml")
        System.loadLibrary("llama")
        System.loadLibrary("buddy_jni")
    }

    external fun nativeLoadModel(modelPath: String, nThreads: Int): Boolean
    external fun nativeGenerateStream(prompt: String, maxNewTokens: Int, callback: TokenCallback?): String
    external fun nativeAbort()
    external fun nativeResetKv()
}

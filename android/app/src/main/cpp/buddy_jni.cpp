#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <cstring>

#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "BuddyJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BuddyJNI", __VA_ARGS__)

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static std::vector<llama_token> g_kv_tokens;
static std::atomic<bool> g_abort{false};
static bool g_backend_inited = false;

static constexpr int N_CTX_SIZE = 4096;
static constexpr int N_BATCH_SIZE = 512;

static bool is_valid_utf8(const std::string & s) {
    const unsigned char * bytes = reinterpret_cast<const unsigned char *>(s.c_str());
    size_t len = s.size();
    size_t i = 0;
    while (i < len) {
        unsigned char c = bytes[i];
        size_t n = 0;
        if ((c & 0x80) == 0x00) n = 1;
        else if ((c & 0xE0) == 0xC0) n = 2;
        else if ((c & 0xF0) == 0xE0) n = 3;
        else if ((c & 0xF8) == 0xF0) n = 4;
        else return false;
        if (i + n > len) return false;
        for (size_t j = 1; j < n; ++j) {
            if ((bytes[i + j] & 0xC0) != 0x80) return false;
        }
        i += n;
    }
    return true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_phonebuddy_app_LlamaBridge_nativeLoadModel(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jModelPath,
        jint nThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
    }

    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_kv_tokens.clear();

    const char * path = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Loading GGUF model from %s", path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jModelPath, path);

    if (g_model == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = N_CTX_SIZE;
    cparams.n_batch = N_BATCH_SIZE;
    cparams.n_ubatch = N_BATCH_SIZE;
    int threads = std::max(2, std::min(6, (int) nThreads));
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        LOGE("Failed to init llama_context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded! n_ctx=%u threads=%d", llama_n_ctx(g_ctx), threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_phonebuddy_app_LlamaBridge_nativeAbort(JNIEnv *, jobject) {
    g_abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_phonebuddy_app_LlamaBridge_nativeResetKv(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_kv_tokens.clear();
    if (g_ctx != nullptr) {
        llama_memory_clear(llama_get_memory(g_ctx), false);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phonebuddy_app_LlamaBridge_nativeGenerateStream(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jPrompt,
        jint maxNewTokens,
        jobject callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_ctx == nullptr) {
        return env->NewStringUTF("[Offline model not loaded yet]");
    }

    g_abort.store(false);

    const char * rawPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(rawPrompt ? rawPrompt : "");
    env->ReleaseStringUTFChars(jPrompt, rawPrompt);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);

    int32_t est = (int32_t) prompt.size() + 32;
    std::vector<llama_token> tokens(est);
    int32_t n_tok = llama_tokenize(
        vocab,
        prompt.c_str(),
        (int32_t) prompt.size(),
        tokens.data(),
        (int32_t) tokens.size(),
        /*add_special=*/false,
        /*parse_special=*/true
    );
    if (n_tok < 0) {
        tokens.resize(-n_tok);
        n_tok = llama_tokenize(
            vocab,
            prompt.c_str(),
            (int32_t) prompt.size(),
            tokens.data(),
            (int32_t) tokens.size(),
            /*add_special=*/false,
            /*parse_special=*/true
        );
    }
    if (n_tok <= 0) {
        return env->NewStringUTF("");
    }
    tokens.resize(n_tok);

    // Keep tail if prompt exceeds context window
    const int maxPrompt = N_CTX_SIZE - std::max(64, (int) maxNewTokens) - 16;
    if ((int) tokens.size() > maxPrompt) {
        tokens.erase(tokens.begin(), tokens.end() - maxPrompt);
        g_kv_tokens.clear();
        llama_memory_clear(llama_get_memory(g_ctx), false);
    }

    // Reuse matching KV-cache prefix for instant multi-turn latency
    size_t common = 0;
    size_t maxCommon = std::min(g_kv_tokens.size(), tokens.size());
    while (common < maxCommon && g_kv_tokens[common] == tokens[common]) {
        common++;
    }
    if (common >= tokens.size() && common > 0) {
        common = tokens.size() - 1;
    }

    if (common < g_kv_tokens.size()) {
        llama_memory_seq_rm(llama_get_memory(g_ctx), 0, (llama_pos) common, -1);
        g_kv_tokens.resize(common);
    }

    // Decode new prompt tokens in batches
    llama_batch batch = llama_batch_init(N_BATCH_SIZE, 0, 1);
    for (size_t start = common; start < tokens.size(); start += N_BATCH_SIZE) {
        if (g_abort.load()) {
            llama_batch_free(batch);
            return env->NewStringUTF("");
        }
        size_t cur = std::min((size_t) N_BATCH_SIZE, tokens.size() - start);
        batch.n_tokens = (int32_t) cur;
        for (size_t j = 0; j < cur; ++j) {
            batch.token[j] = tokens[start + j];
            batch.pos[j] = (llama_pos) (start + j);
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (start + j == tokens.size() - 1) ? 1 : 0;
        }
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during prompt prefill");
            llama_batch_free(batch);
            g_kv_tokens.clear();
            llama_memory_clear(llama_get_memory(g_ctx), false);
            return env->NewStringUTF("[Decode error]");
        }
    }
    g_kv_tokens = tokens;

    // Sampler matching v0.5.1 server parameters (top_k=40, top_p=0.85, rep_pen=1.06, temp=0.45)
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.85f, 1));
    llama_sampler_chain_add(
        smpl,
        llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.06f, 0.0f, 0.0f)
    );
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.45f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Seed penalty history with recent prompt tokens
    size_t tailStart = g_kv_tokens.size() > 64 ? g_kv_tokens.size() - 64 : 0;
    for (size_t idx = tailStart; idx < g_kv_tokens.size(); ++idx) {
        llama_sampler_accept(smpl, g_kv_tokens[idx]);
    }

    jclass cbClass = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID onTokenMid = cbClass ? env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V") : nullptr;

    std::string fullReply;
    std::string utf8Pending;

    for (int step = 0; step < maxNewTokens; ++step) {
        if (g_abort.load()) break;
        if ((int) g_kv_tokens.size() >= N_CTX_SIZE - 4) break;

        llama_token newId = llama_sampler_sample(smpl, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, newId)) {
            break;
        }

        char pieceBuf[256];
        int32_t nChars = llama_token_to_piece(vocab, newId, pieceBuf, sizeof(pieceBuf), 0, /*special=*/false);
        if (nChars > 0) {
            utf8Pending.append(pieceBuf, nChars);
            if (is_valid_utf8(utf8Pending)) {
                fullReply += utf8Pending;
                if (callback && onTokenMid) {
                    jstring jPiece = env->NewStringUTF(utf8Pending.c_str());
                    env->CallVoidMethod(callback, onTokenMid, jPiece);
                    env->DeleteLocalRef(jPiece);
                }
                utf8Pending.clear();
            }
        }

        batch.n_tokens = 1;
        batch.token[0] = newId;
        batch.pos[0] = (llama_pos) g_kv_tokens.size();
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during token generation");
            break;
        }
        g_kv_tokens.push_back(newId);
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    return env->NewStringUTF(fullReply.c_str());
}

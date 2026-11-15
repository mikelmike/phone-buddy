#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <cstring>

#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "BuddyJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BuddyJNI", __VA_ARGS__)

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static std::atomic<bool> g_abort{false};
static bool g_backend_inited = false;

// Exact prefix snapshot & multi-turn continuation state (eliminates ~900-token system prefill on mobile CPU)
static std::vector<llama_token> g_sys_tokens;
static std::vector<uint8_t> g_sys_snapshot;
static std::vector<llama_token> g_active_tokens;

static constexpr int N_CTX_SIZE = 2048;
static constexpr int N_BATCH_SIZE = 1024;

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

static std::vector<llama_token> tokenize_exact(const llama_vocab * vocab, const std::string & text) {
    if (text.empty()) return {};
    int32_t est = (int32_t) text.size() + 32;
    std::vector<llama_token> toks(est);
    int32_t n = llama_tokenize(
        vocab, text.c_str(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(),
        /*add_special=*/false, /*parse_special=*/true
    );
    if (n < 0) {
        toks.resize(-n);
        n = llama_tokenize(
            vocab, text.c_str(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(),
            /*add_special=*/false, /*parse_special=*/true
        );
    }
    if (n <= 0) return {};
    toks.resize(n);
    return toks;
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

    g_sys_tokens.clear();
    g_sys_snapshot.clear();
    g_active_tokens.clear();

    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    const char * path = env->GetStringUTFChars(jModelPath, nullptr);
    LOGI("Loading 1.2B GGUF model from %s", path);

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
    // Target the 4 big Cortex-X4 / A720 performance cores on Snapdragon 8 Gen 3
    int threads = std::max(2, std::min(4, (int) nThreads));
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        LOGE("Failed to init llama_context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded! n_ctx=%u threads=%d hybrid=%d",
         llama_n_ctx(g_ctx), threads, (int) llama_model_is_hybrid(g_model));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_phonebuddy_app_LlamaBridge_nativeWarmupPrefix(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jPrefix) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || g_ctx == nullptr) return JNI_FALSE;

    const char * raw = env->GetStringUTFChars(jPrefix, nullptr);
    std::string prefix(raw ? raw : "");
    env->ReleaseStringUTFChars(jPrefix, raw);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> p_toks = tokenize_exact(vocab, prefix);
    if (p_toks.empty() || (int) p_toks.size() >= N_CTX_SIZE - 256) return JNI_FALSE;

    if (p_toks == g_sys_tokens && !g_sys_snapshot.empty()) {
        return JNI_TRUE;
    }

    auto t0 = std::chrono::steady_clock::now();
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_active_tokens.clear();
    g_sys_tokens.clear();
    g_sys_snapshot.clear();

    llama_batch batch = llama_batch_init(N_BATCH_SIZE, 0, 1);
    for (size_t start = 0; start < p_toks.size(); start += N_BATCH_SIZE) {
        size_t cur = std::min((size_t) N_BATCH_SIZE, p_toks.size() - start);
        batch.n_tokens = (int32_t) cur;
        for (size_t j = 0; j < cur; ++j) {
            batch.token[j] = p_toks[start + j];
            batch.pos[j] = (llama_pos) (start + j);
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (start + j == p_toks.size() - 1) ? 1 : 0;
        }
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("Warmup decode failed");
            llama_batch_free(batch);
            llama_memory_clear(llama_get_memory(g_ctx), true);
            return JNI_FALSE;
        }
    }
    llama_batch_free(batch);

    size_t sz = llama_state_seq_get_size(g_ctx, 0);
    if (sz > 0) {
        g_sys_snapshot.resize(sz);
        size_t copied = llama_state_seq_get_data(g_ctx, g_sys_snapshot.data(), sz, 0);
        if (copied > 0) {
            g_sys_snapshot.resize(copied);
            g_sys_tokens = p_toks;
            g_active_tokens = p_toks;
            long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - t0
            ).count();
            LOGI("Warmup cached %zu system tokens (%zu bytes) in %ldms",
                 g_sys_tokens.size(), g_sys_snapshot.size(), ms);
            return JNI_TRUE;
        }
    }
    g_sys_snapshot.clear();
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_phonebuddy_app_LlamaBridge_nativeAbort(JNIEnv *, jobject) {
    g_abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_phonebuddy_app_LlamaBridge_nativeResetKv(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_active_tokens.clear();
    if (g_ctx != nullptr) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
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
    auto tStart = std::chrono::steady_clock::now();

    const char * rawPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(rawPrompt ? rawPrompt : "");
    env->ReleaseStringUTFChars(jPrompt, rawPrompt);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens = tokenize_exact(vocab, prompt);
    if (tokens.empty()) {
        return env->NewStringUTF("");
    }

    const int maxPrompt = N_CTX_SIZE - std::max(64, (int) maxNewTokens) - 16;
    bool truncated = false;
    if ((int) tokens.size() > maxPrompt) {
        tokens.erase(tokens.begin(), tokens.end() - maxPrompt);
        truncated = true;
    }

    size_t startOffset = 0;
    const char * cacheMode = "full";
    if (!truncated
            && !g_active_tokens.empty()
            && tokens.size() > g_active_tokens.size()
            && std::equal(g_active_tokens.begin(), g_active_tokens.end(), tokens.begin())) {
        startOffset = g_active_tokens.size();
        cacheMode = "cont";
    } else if (!truncated
            && !g_sys_tokens.empty()
            && !g_sys_snapshot.empty()
            && tokens.size() > g_sys_tokens.size()
            && std::equal(g_sys_tokens.begin(), g_sys_tokens.end(), tokens.begin())) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        size_t loaded = llama_state_seq_set_data(g_ctx, g_sys_snapshot.data(), g_sys_snapshot.size(), 0);
        if (loaded > 0) {
            g_active_tokens = g_sys_tokens;
            startOffset = g_sys_tokens.size();
            cacheMode = "snap";
        } else {
            llama_memory_clear(llama_get_memory(g_ctx), true);
            g_active_tokens.clear();
            startOffset = 0;
        }
    } else {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_active_tokens.clear();
        startOffset = 0;
    }

    llama_batch batch = llama_batch_init(N_BATCH_SIZE, 0, 1);
    for (size_t start = startOffset; start < tokens.size(); start += N_BATCH_SIZE) {
        if (g_abort.load()) {
            llama_batch_free(batch);
            g_active_tokens.clear();
            llama_memory_clear(llama_get_memory(g_ctx), true);
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
            g_active_tokens.clear();
            llama_memory_clear(llama_get_memory(g_ctx), true);
            return env->NewStringUTF("[Decode error]");
        }
    }
    g_active_tokens = tokens;

    auto tPrefill = std::chrono::steady_clock::now();
    long prefillMs = std::chrono::duration_cast<std::chrono::milliseconds>(tPrefill - tStart).count();

    // Canonical llama.cpp sampler order matching app/server.py v0.6.0 (1.2B):
    // logit_bias (ban em-dashes) -> penalties(1.06) -> top_k(50) -> top_p(0.85) -> temp(0.35) -> dist
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);

    std::vector<llama_logit_bias> biases;
    const char * banned_strs[] = {"—", " —", "— ", "--", " --"};
    for (const char * bs : banned_strs) {
        llama_token tmp_toks[8];
        int32_t nt = llama_tokenize(vocab, bs, (int32_t) std::strlen(bs), tmp_toks, 8, false, false);
        if (nt == 1) {
            biases.push_back({tmp_toks[0], -INFINITY});
        }
    }
    if (!biases.empty()) {
        llama_sampler_chain_add(
            smpl,
            llama_sampler_init_logit_bias(llama_vocab_n_tokens(vocab), (int32_t) biases.size(), biases.data())
        );
    }

    llama_sampler_chain_add(
        smpl,
        llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.06f, 0.0f, 0.0f)
    );
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(50));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.85f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.35f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    size_t tailStart = tokens.size() > 64 ? tokens.size() - 64 : 0;
    for (size_t idx = tailStart; idx < tokens.size(); ++idx) {
        llama_sampler_accept(smpl, tokens[idx]);
    }

    jclass cbClass = callback ? env->GetObjectClass(callback) : nullptr;
    jmethodID onTokenMid = cbClass ? env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V") : nullptr;

    std::string fullReply;
    std::string utf8Pending;
    llama_pos curPos = (llama_pos) tokens.size();
    int genTokens = 0;

    for (int step = 0; step < maxNewTokens; ++step) {
        if (g_abort.load()) break;
        if (curPos >= N_CTX_SIZE - 4) break;

        llama_token newId = llama_sampler_sample(smpl, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, newId)) {
            break;
        }
        genTokens++;

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
        batch.pos[0] = curPos++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during token generation");
            g_active_tokens.clear();
            break;
        }
        g_active_tokens.push_back(newId);
    }

    auto tDone = std::chrono::steady_clock::now();
    long genMs = std::chrono::duration_cast<std::chrono::milliseconds>(tDone - tPrefill).count();
    long totalMs = std::chrono::duration_cast<std::chrono::milliseconds>(tDone - tStart).count();
    double tokPerSec = genMs > 0 ? (genTokens * 1000.0 / genMs) : 0.0;
    LOGI("Turn done [%s]: prompt_tok=%zu new_prefill=%zu prefill=%ldms gen_tok=%d gen=%ldms (%.1f tok/s) total=%ldms | reply=%s",
         cacheMode, tokens.size(), tokens.size() - startOffset, prefillMs, genTokens, genMs, tokPerSec, totalMs, fullReply.c_str());

    llama_sampler_free(smpl);
    llama_batch_free(batch);
    return env->NewStringUTF(fullReply.c_str());
}

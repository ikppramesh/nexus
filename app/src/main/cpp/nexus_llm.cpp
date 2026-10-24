// JNI bridge to llama.cpp for the on-device IRx model. One conversation at a time; the KV cache
// is reused across turns when the new prompt starts with the previous one.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"
#include "chat.h"

#define LOG_TAG "NexusLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string tmpl;
    common_chat_templates_ptr templates;  // the model's own Jinja template
    std::vector<llama_token> cached;      // tokens currently in the KV cache
};

llama_sampler *make_sampler(const llama_vocab *vocab, float temp) {
    // Matches the model card's general.sampling settings.
    auto *s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(s, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(s, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(s, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(s, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return s;
}

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// The model's own Jinja template, with thinking switched off (a spoken reply can't wait for it).
// Empty if the model has no usable template.
std::string format_jinja(Session *s, const std::vector<std::string> &roles, const std::vector<std::string> &contents) {
    if (!s->templates) return "";
    try {
        common_chat_templates_inputs in;
        for (size_t i = 0; i < roles.size(); i++) {
            common_chat_msg m;
            m.role = roles[i];
            m.content = contents[i];
            in.messages.push_back(m);
        }
        in.add_generation_prompt = true;
        in.use_jinja = true;
        in.enable_thinking = false;
        return common_chat_templates_apply(s->templates.get(), in).prompt;
    } catch (const std::exception &e) {
        LOGE("jinja template failed: %s", e.what());
        return "";
    }
}

// llama.cpp's built-in formats: the model's family if recognised, else ChatML.
std::string format_builtin(Session *s, const std::vector<llama_chat_message> &msgs) {
    std::vector<char> buf(8192);
    const char *tmpl = s->tmpl.c_str();
    int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    if (len < 0) {
        tmpl = "chatml";
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    }
    if (len > (int) buf.size()) {
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), buf.size());
    }
    return len < 0 ? "" : std::string(buf.data(), len);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ramesh_nexus_LocalLlm_nativeLoad(JNIEnv *env, jobject, jstring path, jint threads, jint n_ctx) {
    llama_backend_init();
    auto mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!model) {
        LOGE("failed to load model");
        return 0;
    }
    auto cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.swa_full = true;  // Gemma 3 sliding-window cache must allow trimming for prefix reuse
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        LOGE("failed to create context");
        return 0;
    }
    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    const char *t = llama_model_chat_template(model, nullptr);
    s->tmpl = t ? t : "";
    try {
        s->templates = common_chat_templates_init(model, "");
    } catch (const std::exception &e) {
        LOGE("chat template not usable (%s); using built-in formats", e.what());
    }
    LOGI("model loaded, ctx=%d threads=%d", n_ctx, threads);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ramesh_nexus_LocalLlm_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto *s = reinterpret_cast<Session *>(ptr);
    if (!s) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

// Streams the reply to `roles`/`contents` as raw UTF-8 byte pieces to sink.onPiece(byte[]): Boolean
// (return false to stop). Returns the number of generated tokens, or -1 on error.
extern "C" JNIEXPORT jint JNICALL
Java_com_ramesh_nexus_LocalLlm_nativeGenerate(JNIEnv *env, jobject, jlong ptr, jobjectArray roles,
                                              jobjectArray contents, jint max_tokens, jfloat temp,
                                              jobject sink) {
    auto *s = reinterpret_cast<Session *>(ptr);
    if (!s) return -1;

    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        r[i] = jstr(env, (jstring) env->GetObjectArrayElement(roles, i));
        c[i] = jstr(env, (jstring) env->GetObjectArrayElement(contents, i));
        msgs[i] = {r[i].c_str(), c[i].c_str()};
    }
    std::string prompt = format_jinja(s, r, c);
    if (prompt.empty()) prompt = format_builtin(s, msgs);
    if (prompt.empty()) {
        LOGE("chat template failed");
        return -1;
    }

    // Add <bos> only if the model wants one and the template didn't already write it.
    const char *bos = llama_vocab_get_text(s->vocab, llama_vocab_bos(s->vocab));
    const bool add_bos = llama_vocab_get_add_bos(s->vocab) && !(bos && prompt.rfind(bos, 0) == 0);
    int n_tok = -llama_tokenize(s->vocab, prompt.c_str(), prompt.size(), nullptr, 0, add_bos, true);
    std::vector<llama_token> tokens(n_tok);
    llama_tokenize(s->vocab, prompt.c_str(), prompt.size(), tokens.data(), n_tok, add_bos, true);

    const int n_ctx = (int) llama_n_ctx(s->ctx);
    if (n_tok + max_tokens > n_ctx) {
        LOGE("prompt too long: %d tokens", n_tok);
        return -1;
    }

    // Reuse the cached prefix; always re-decode at least the last token to get fresh logits.
    size_t keep = 0;
    while (keep < s->cached.size() && keep < tokens.size() && s->cached[keep] == tokens[keep]) keep++;
    if (keep == tokens.size()) keep--;
    auto *mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
        llama_memory_clear(mem, false);
        keep = 0;
    }
    s->cached.assign(tokens.begin(), tokens.begin() + keep);

    for (size_t i = keep; i < tokens.size(); i += 512) {
        int count = (int) std::min<size_t>(512, tokens.size() - i);
        if (llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, count)) != 0) {
            LOGE("prompt decode failed");
            llama_memory_clear(mem, false);
            s->cached.clear();
            return -1;
        }
        s->cached.insert(s->cached.end(), tokens.begin() + i, tokens.begin() + i + count);
    }
    LOGI("prompt %d tokens, %zu reused", n_tok, keep);

    jclass cls = env->GetObjectClass(sink);
    jmethodID on_piece = env->GetMethodID(cls, "onPiece", "([B)Z");
    llama_sampler *smpl = make_sampler(s->vocab, temp);
    int generated = 0;
    char piece[256];
    while (generated < max_tokens) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;
        int plen = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, false);
        if (plen > 0) {
            jbyteArray bytes = env->NewByteArray(plen);
            env->SetByteArrayRegion(bytes, 0, plen, reinterpret_cast<const jbyte *>(piece));
            jboolean more = env->CallBooleanMethod(sink, on_piece, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck() || !more) break;
        }
        generated++;
        if (llama_decode(s->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
        s->cached.push_back(tok);
    }
    llama_sampler_free(smpl);
    return generated;
}

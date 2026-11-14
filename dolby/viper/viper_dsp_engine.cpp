/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 *
 * Unified DSP Engine & AudioEffect library bridging Dolby Advanced Settings
 * (ViperEffectModel.kt -> ViperController.kt) to dolby/viper/effects/ + utils/.
 */

#include <jni.h>
#include <android/log.h>
#include <hardware/audio_effect.h>
#include <system/audio.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#include "constants.h"
#include "effects/ViPERBass.h"
#include "effects/ViPERClarity.h"
#include "effects/DynamicSystem.h"
#include "effects/SpeakerCorrection.h"
#include "utils/DynamicBass.h"

#define LOG_TAG "ViPER_DSP_Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Command IDs matching ViperController.kt & ViperEffectModel.kt
enum ViperCommandId : uint32_t {
    PARAM_DYNAMIC_ENABLE   = 0x10021,
    PARAM_DYNAMIC_X_COEFFS = 0x10022,
    PARAM_DYNAMIC_Y_COEFFS = 0x10023,
    PARAM_DYNAMIC_SIDEGAIN = 0x10024,
    PARAM_DYNAMIC_STRENGTH = 0x10025,
    PARAM_BASS_ENABLE      = 0x10026,
    PARAM_BASS_MODE        = 0x10027,
    PARAM_BASS_FREQUENCY   = 0x10028,
    PARAM_BASS_GAIN        = 0x10029,
    PARAM_CLARITY_ENABLE   = 0x1002A,
    PARAM_CLARITY_MODE     = 0x1002B,
    PARAM_CLARITY_GAIN     = 0x1002C,
    PARAM_SPEAKER_ENABLE   = 0x1002D,
    PARAM_SPEAKER_STRENGTH = 0x1002E,
};

class ViperDspEngine {
public:
    ViperDspEngine()
        : samplingRate_(48000),
          dynamicEnabled_(false),
          speakerCorrectionEnabled_(false),
          speakerStrength_(0.0f) {
        SetSamplingRate(48000);
        // Configure DynamicSystem / DynamicBass defaults for ViPER4Android Dynamic System
        dynamicSystem_.SetXCoeffs(120, 80);
        dynamicSystem_.SetYCoeffs(40, samplingRate_ / 4);
        dynamicSystem_.SetSideGain(1.0f, 1.0f);
        dynamicSystem_.SetBassGain(1.0f);

        dynamicBass_.SetFilterXPassFrequency(120, 80);
        dynamicBass_.SetFilterYPassFrequency(40, samplingRate_ / 4);
        dynamicBass_.SetSideGain(1.0f, 1.0f);
        dynamicBass_.SetBassGain(1.0f);
    }

    void SetSamplingRate(uint32_t sr) {
        if (sr == 0) sr = 48000;
        samplingRate_ = sr;
        viperBass_.SetSamplingRate(sr);
        viperClarity_.SetSamplingRate(sr);
        dynamicSystem_.SetSamplingRate(sr);
        dynamicBass_.SetSamplingRate(sr);
        speakerCorrection_.SetSamplingRate(sr);
    }

    void Reset() {
        viperBass_.Reset();
        viperClarity_.Reset();
        dynamicSystem_.Reset();
        dynamicBass_.Reset();
        speakerCorrection_.Reset();
    }

    void DispatchCommand(uint32_t param, int32_t v1, int32_t v2, int32_t v3, int32_t v4) {
        (void)v3;
        (void)v4;
        switch (param) {
            case PARAM_BASS_ENABLE:
                viperBass_.SetEnable(v1 != 0);
                break;
            case PARAM_BASS_MODE: {
                int mode = v1 < 0 ? 0 : (v1 > 2 ? 2 : v1);
                viperBass_.SetProcessMode(static_cast<ViPERBass::ProcessMode>(mode));
                break;
            }
            case PARAM_BASS_FREQUENCY: {
                uint32_t freq = static_cast<uint32_t>(v1 < 15 ? 15 : (v1 > 150 ? 150 : v1));
                viperBass_.SetSpeaker(freq);
                break;
            }
            case PARAM_BASS_GAIN: {
                // v1 is scaled by 100 (e.g. 300 -> 3.0f bass factor)
                float factor = static_cast<float>(v1) / 100.0f;
                viperBass_.SetBassFactor(factor);
                break;
            }
            case PARAM_CLARITY_ENABLE:
                viperClarity_.SetEnable(v1 != 0);
                break;
            case PARAM_CLARITY_MODE: {
                int mode = v1 < 0 ? 0 : (v1 > 2 ? 2 : v1);
                viperClarity_.SetProcessMode(static_cast<ViPERClarity::ClarityMode>(mode));
                break;
            }
            case PARAM_CLARITY_GAIN: {
                // v1 is scaled by 100 (e.g. 150 -> 1.5f clarity gain)
                float gain = static_cast<float>(v1) / 100.0f;
                viperClarity_.SetClarity(gain);
                break;
            }
            case PARAM_DYNAMIC_ENABLE: {
                bool en = (v1 != 0);
                if (en && !dynamicEnabled_) {
                    dynamicSystem_.Reset();
                    dynamicBass_.Reset();
                }
                dynamicEnabled_ = en;
                dynamicSystem_.SetEnable(en);
                break;
            }
            case PARAM_DYNAMIC_STRENGTH: {
                // Slider 0..100 maps accurately to DynamicBass gain 1.0..11.0
                // (qPeak = ((gain - 1.0) / 20.0) * 1600.0 -> Q = qPeak / 666.0 + 0.5)
                float clamped = static_cast<float>(v1 < 0 ? 0 : (v1 > 100 ? 100 : v1));
                float gain = 1.0f + (clamped / 10.0f);
                dynamicSystem_.SetBassGain(gain);
                dynamicBass_.SetBassGain(gain);
                break;
            }
            case PARAM_DYNAMIC_X_COEFFS: {
                uint32_t low = static_cast<uint32_t>(v1);
                uint32_t high = static_cast<uint32_t>(v2);
                dynamicSystem_.SetXCoeffs(low, high);
                dynamicBass_.SetFilterXPassFrequency(low, high);
                break;
            }
            case PARAM_DYNAMIC_Y_COEFFS: {
                uint32_t low = static_cast<uint32_t>(v1);
                uint32_t high = static_cast<uint32_t>(v2);
                dynamicSystem_.SetYCoeffs(low, high);
                dynamicBass_.SetFilterYPassFrequency(low, high);
                break;
            }
            case PARAM_DYNAMIC_SIDEGAIN: {
                float gx = static_cast<float>(v1) / 100.0f;
                float gy = static_cast<float>(v2) / 100.0f;
                dynamicSystem_.SetSideGain(gx, gy);
                dynamicBass_.SetSideGain(gx, gy);
                break;
            }
            case PARAM_SPEAKER_ENABLE: {
                bool en = (v1 != 0);
                speakerCorrectionEnabled_ = en;
                speakerCorrection_.SetEnable(en);
                break;
            }
            case PARAM_SPEAKER_STRENGTH: {
                float clamped = static_cast<float>(v1 < 0 ? 0 : (v1 > 100 ? 100 : v1));
                speakerStrength_ = clamped / 100.0f;
                bool active = (speakerStrength_ > 0.001f);
                speakerCorrectionEnabled_ = active;
                speakerCorrection_.SetEnable(active);
                break;
            }
            default:
                break;
        }
    }

    // Process stereo interleaved float PCM frames through Effects + Utils
    void ProcessStereoFloat(float* samples, uint32_t frames) {
        if (!samples || frames == 0) return;

        // 1. ViPERBass (effects/ViPERBass.cpp + utils/Biquad, Polyphase, Subwoofer, WaveBuffer)
        viperBass_.Process(samples, frames);

        // 2. DynamicSystem (effects/DynamicSystem.cpp -> delegates directly to utils/DynamicBass.cpp)
        dynamicSystem_.Process(samples, frames);

        // 3. ViPERClarity (effects/ViPERClarity.cpp + utils/NoiseSharpening, HighShelf, HiFi)
        viperClarity_.Process(samples, frames);

        // 4. SpeakerCorrection (effects/SpeakerCorrection.cpp + utils/MultiBiquad & Biquad)
        // Blends dry signal with SpeakerCorrection::Process output according to speakerStrength_ (0.0..1.0)
        if (speakerCorrectionEnabled_ && speakerStrength_ > 0.001f) {
            const uint32_t totalSamples = frames * 2;
            if (dryScratch_.size() < totalSamples) {
                dryScratch_.resize(totalSamples);
            }
            std::memcpy(dryScratch_.data(), samples, totalSamples * sizeof(float));

            speakerCorrection_.Process(samples, frames);

            const float wet = speakerStrength_;
            const float dry = 1.0f - wet;
            if (wet < 0.999f) {
                for (uint32_t i = 0; i < totalSamples; ++i) {
                    samples[i] = dryScratch_[i] * dry + samples[i] * wet;
                }
            }
        }
    }

private:
    uint32_t samplingRate_;
    ViPERBass viperBass_;
    ViPERClarity viperClarity_;
    DynamicSystem dynamicSystem_;
    DynamicBass dynamicBass_;
    SpeakerCorrection speakerCorrection_;
    bool dynamicEnabled_;
    bool speakerCorrectionEnabled_;
    float speakerStrength_;
    std::vector<float> dryScratch_;
};

// ============================================================================
// 1. JNI Bridge for ViperEffectModel.kt (Direct C++ DSP invocation)
// ============================================================================
namespace {
std::mutex gJniEngineMutex;
ViperDspEngine* gJniEngine = nullptr;

ViperDspEngine* GetOrCreateJniEngine() {
    if (!gJniEngine) {
        gJniEngine = new ViperDspEngine();
    }
    return gJniEngine;
}
} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_data_ViperEffectModel_nativeInit(JNIEnv*, jobject, jint sampleRate) {
    std::lock_guard<std::mutex> lock(gJniEngineMutex);
    ViperDspEngine* engine = GetOrCreateJniEngine();
    engine->SetSamplingRate(static_cast<uint32_t>(sampleRate));
    LOGI("ViperEffectModel JNI initialized native DSP engine at %d Hz", sampleRate);
}

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_data_ViperEffectModel_nativeDispatchCommand(
        JNIEnv*, jobject, jint param, jint v1, jint v2, jint v3, jint v4) {
    std::lock_guard<std::mutex> lock(gJniEngineMutex);
    ViperDspEngine* engine = GetOrCreateJniEngine();
    engine->DispatchCommand(static_cast<uint32_t>(param), v1, v2, v3, v4);
}

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_data_ViperEffectModel_nativeProcessBuffer(
        JNIEnv* env, jobject, jfloatArray jBuffer, jint frames) {
    std::lock_guard<std::mutex> lock(gJniEngineMutex);
    if (!gJniEngine || frames <= 0) return;
    jfloat* samples = env->GetFloatArrayElements(jBuffer, nullptr);
    if (!samples) return;
    gJniEngine->ProcessStereoFloat(samples, static_cast<uint32_t>(frames));
    env->ReleaseFloatArrayElements(jBuffer, samples, 0);
}

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_data_ViperEffectModel_nativeRelease(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(gJniEngineMutex);
    delete gJniEngine;
    gJniEngine = nullptr;
}

} // extern "C"

// ============================================================================
// 2. Android AudioEffect HAL Library Interface
// ============================================================================
namespace {

struct ViperEffectContext {
    const struct effect_interface_s* itfe;
    effect_config_t config;
    bool enabled;
    ViperDspEngine* engine;
    std::vector<float> floatScratch;
};

// UUID matching DolbyConstants.VIPER_EFFECT_UUID: 90380da3-8536-4744-a6a3-5731970e640f
const effect_descriptor_t gViperDescriptor = {
    EFFECT_UUID_INITIALIZER,
    {0x90380da3, 0x8536, 0x4744, 0xa6a3, {0x57, 0x31, 0x97, 0x0e, 0x64, 0x0f}},
    EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_VOLUME_IND,
    0,
    1,
    "ViPER4Android FX (Dolby Integrated)",
    "Lunaris AOSP / ViPER.WYF"
};

int32_t ViperEffect_Process(effect_handle_t self, audio_buffer_t* inBuffer, audio_buffer_t* outBuffer) {
    auto* ctx = reinterpret_cast<ViperEffectContext*>(self);
    if (!ctx || !inBuffer || !outBuffer || !inBuffer->raw || !outBuffer->raw) {
        return -EINVAL;
    }

    const uint32_t frames = inBuffer->frameCount;
    const uint32_t samplesCount = frames * 2;

    if (!ctx->enabled) {
        if (inBuffer->raw != outBuffer->raw) {
            std::memcpy(outBuffer->raw, inBuffer->raw, samplesCount * sizeof(int16_t));
        }
        outBuffer->frameCount = frames;
        return 0;
    }

    if (ctx->config.inputCfg.format == AUDIO_FORMAT_PCM_FLOAT) {
        float* inF = reinterpret_cast<float*>(inBuffer->raw);
        float* outF = reinterpret_cast<float*>(outBuffer->raw);
        if (inF != outF) {
            std::memcpy(outF, inF, samplesCount * sizeof(float));
        }
        ctx->engine->ProcessStereoFloat(outF, frames);
    } else {
        if (ctx->floatScratch.size() < samplesCount) {
            ctx->floatScratch.resize(samplesCount);
        }
        const int16_t* in16 = reinterpret_cast<const int16_t*>(inBuffer->raw);
        int16_t* out16 = reinterpret_cast<int16_t*>(outBuffer->raw);
        for (uint32_t i = 0; i < samplesCount; ++i) {
            ctx->floatScratch[i] = static_cast<float>(in16[i]) / 32768.0f;
        }
        ctx->engine->ProcessStereoFloat(ctx->floatScratch.data(), frames);
        for (uint32_t i = 0; i < samplesCount; ++i) {
            float v = ctx->floatScratch[i] * 32768.0f;
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            out16[i] = static_cast<int16_t>(v);
        }
    }

    outBuffer->frameCount = frames;
    return 0;
}

int32_t ViperEffect_Command(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
                            void* pCmdData, uint32_t* replySize, void* pReplyData) {
    auto* ctx = reinterpret_cast<ViperEffectContext*>(self);
    if (!ctx) return -EINVAL;

    switch (cmdCode) {
        case EFFECT_CMD_INIT:
            if (pReplyData && replySize && *replySize >= sizeof(int)) {
                *reinterpret_cast<int*>(pReplyData) = 0;
            }
            return 0;

        case EFFECT_CMD_SET_CONFIG: {
            if (!pCmdData || cmdSize != sizeof(effect_config_t)) return -EINVAL;
            std::memcpy(&ctx->config, pCmdData, sizeof(effect_config_t));
            ctx->engine->SetSamplingRate(ctx->config.inputCfg.samplingRate);
            if (pReplyData && replySize && *replySize >= sizeof(int)) {
                *reinterpret_cast<int*>(pReplyData) = 0;
            }
            return 0;
        }

        case EFFECT_CMD_GET_CONFIG: {
            if (!pReplyData || !replySize || *replySize < sizeof(effect_config_t)) return -EINVAL;
            std::memcpy(pReplyData, &ctx->config, sizeof(effect_config_t));
            return 0;
        }

        case EFFECT_CMD_RESET:
            ctx->engine->Reset();
            return 0;

        case EFFECT_CMD_ENABLE:
            ctx->enabled = true;
            if (pReplyData && replySize && *replySize >= sizeof(int)) {
                *reinterpret_cast<int*>(pReplyData) = 0;
            }
            return 0;

        case EFFECT_CMD_DISABLE:
            ctx->enabled = false;
            if (pReplyData && replySize && *replySize >= sizeof(int)) {
                *reinterpret_cast<int*>(pReplyData) = 0;
            }
            return 0;

        case EFFECT_CMD_SET_PARAM: {
            if (!pCmdData || cmdSize < sizeof(effect_param_t) + 20) return -EINVAL;
            auto* param = reinterpret_cast<effect_param_t*>(pCmdData);
            if (param->psize < 4 || param->vsize < 16) return -EINVAL;
            const uint32_t cmdId = *reinterpret_cast<const uint32_t*>(param->data);
            const int32_t* vals = reinterpret_cast<const int32_t*>(param->data + 4);
            ctx->engine->DispatchCommand(cmdId, vals[0], vals[1], vals[2], vals[3]);
            if (pReplyData && replySize && *replySize >= sizeof(int)) {
                *reinterpret_cast<int*>(pReplyData) = 0;
            }
            return 0;
        }

        case EFFECT_CMD_GET_PARAM: {
            if (pReplyData && replySize && *replySize >= sizeof(effect_param_t)) {
                auto* reply = reinterpret_cast<effect_param_t*>(pReplyData);
                reply->status = 0;
                reply->vsize = 0;
            }
            return 0;
        }

        default:
            return -EINVAL;
    }
}

int32_t ViperEffect_GetDescriptor(effect_handle_t self, effect_descriptor_t* pDescriptor) {
    if (!self || !pDescriptor) return -EINVAL;
    std::memcpy(pDescriptor, &gViperDescriptor, sizeof(effect_descriptor_t));
    return 0;
}

const struct effect_interface_s gViperInterface = {
    ViperEffect_Process,
    ViperEffect_Command,
    ViperEffect_GetDescriptor,
    nullptr
};

int32_t ViperLib_Create(const effect_uuid_t* uuid, int32_t, int32_t, effect_handle_t* pHandle) {
    if (!uuid || !pHandle) return -EINVAL;
    if (std::memcmp(uuid, &gViperDescriptor.uuid, sizeof(effect_uuid_t)) != 0) {
        return -ENOENT;
    }
    auto* ctx = new ViperEffectContext();
    ctx->itfe = &gViperInterface;
    ctx->enabled = false;
    ctx->engine = new ViperDspEngine();
    std::memset(&ctx->config, 0, sizeof(effect_config_t));
    ctx->config.inputCfg.samplingRate = 48000;
    ctx->config.outputCfg.samplingRate = 48000;
    ctx->config.inputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    ctx->config.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    ctx->config.inputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    ctx->config.outputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    *pHandle = reinterpret_cast<effect_handle_t>(ctx);
    LOGI("Created ViPER AudioEffect session for system audio output");
    return 0;
}

int32_t ViperLib_Release(effect_handle_t handle) {
    auto* ctx = reinterpret_cast<ViperEffectContext*>(handle);
    if (!ctx) return -EINVAL;
    delete ctx->engine;
    delete ctx;
    return 0;
}

int32_t ViperLib_GetDescriptor(const effect_uuid_t* uuid, effect_descriptor_t* pDescriptor) {
    if (!uuid || !pDescriptor) return -EINVAL;
    if (std::memcmp(uuid, &gViperDescriptor.uuid, sizeof(effect_uuid_t)) != 0) {
        return -ENOENT;
    }
    std::memcpy(pDescriptor, &gViperDescriptor, sizeof(effect_descriptor_t));
    return 0;
}

} // namespace

extern "C" __attribute__((visibility("default")))
audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_LIBRARY_API_VERSION,
    .name = "ViPER4Android FX Library",
    .implementor = "Lunaris AOSP",
    .create_effect = ViperLib_Create,
    .release_effect = ViperLib_Release,
    .get_descriptor = ViperLib_GetDescriptor,
};

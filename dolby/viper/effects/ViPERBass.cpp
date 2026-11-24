#include "ViPERBass.h"
#include "../constants.h"
#include <algorithm>

// Iscle: Verified with the latest version at 13/12/2022

ViPERBass::ViPERBass() : polyphase(2), waveBuffer(2, 4096) {
    this->speaker = 60;
    this->enable = false;
    this->processMode = ProcessMode::NATURAL_BASS;
    this->antiPop = 0.0;
    this->bassFactor = 0.0;
    this->samplingRate = VIPER_DEFAULT_SAMPLING_RATE;
    this->samplingRatePeriod = 1.0 / VIPER_DEFAULT_SAMPLING_RATE;

    for (auto &biquad : this->biquad) {
        biquad.Reset();
        biquad.SetLowPassParameter((float) this->speaker, this->samplingRate, 0.707f);
    }
    this->subwoofer.SetBassGain(this->samplingRate, 0.0);
    Reset();
}

void ViPERBass::Process(float *samples, uint32_t size) {
    if (!this->enable) return;
    if (size == 0) return;

    const float rampStep = 100.0f * this->samplingRatePeriod;

    switch (this->processMode) {
        case ProcessMode::NATURAL_BASS: {
            for (uint32_t i = 0; i < size * 2; i += 2) {
                float gain = this->bassFactor * this->antiPop;
                samples[i] += (float) this->biquad[0].ProcessSample(samples[i]) * gain;
                samples[i + 1] += (float) this->biquad[1].ProcessSample(samples[i + 1]) * gain;
                if (this->antiPop < 1.0f) {
                    this->antiPop = std::min(1.0f, this->antiPop + rampStep);
                }
            }
            break;
        }
        case ProcessMode::PURE_BASS_PLUS: {
            if (this->waveBuffer.PushSamples(samples, size)) {
                float *buffer = this->waveBuffer.GetBuffer();
                uint32_t bufferOffset = this->waveBuffer.GetBufferOffset();
                uint32_t sampleOffset = (bufferOffset >= size) ? (bufferOffset - size) * 2 : 0;
                uint32_t maxSamples = this->waveBuffer.GetBufferSize() * 2;

                for (uint32_t i = 0; i < size * 2; i += 2) {
                    if (sampleOffset + i + 1 < maxSamples) {
                        buffer[sampleOffset + i] = (float) this->biquad[0].ProcessSample(samples[i]);
                        buffer[sampleOffset + i + 1] = (float) this->biquad[1].ProcessSample(samples[i + 1]);
                    }
                }

                if (this->polyphase.Process(samples, size) == size) {
                    for (uint32_t i = 0; i < size * 2; i += 2) {
                        if (i + 1 < maxSamples) {
                            float gain = this->bassFactor * this->antiPop;
                            samples[i] += buffer[i] * gain;
                            samples[i + 1] += buffer[i + 1] * gain;
                        }
                        if (this->antiPop < 1.0f) {
                            this->antiPop = std::min(1.0f, this->antiPop + rampStep);
                        }
                    }
                    this->waveBuffer.PopSamples(size, true);
                }
            }
            break;
        }
        case ProcessMode::SUBWOOFER: {
            if (this->antiPop >= 1.0f) {
                this->subwoofer.Process(samples, size);
            } else {
                constexpr uint32_t kChunk = 128;
                float dry[kChunk * 2];
                uint32_t offset = 0;
                while (offset < size) {
                    uint32_t count = std::min(kChunk, size - offset);
                    float *chunk = samples + offset * 2;
                    std::copy_n(chunk, count * 2, dry);
                    this->subwoofer.Process(chunk, count);
                    for (uint32_t i = 0; i < count * 2; i += 2) {
                        chunk[i] = dry[i] + (chunk[i] - dry[i]) * this->antiPop;
                        chunk[i + 1] = dry[i + 1] + (chunk[i + 1] - dry[i + 1]) * this->antiPop;
                        if (this->antiPop < 1.0f) {
                            this->antiPop = std::min(1.0f, this->antiPop + rampStep);
                        }
                    }
                    offset += count;
                }
            }
            break;
        }
    }
}

void ViPERBass::Reset() {
    this->polyphase.SetSamplingRate(this->samplingRate);
    this->polyphase.Reset();
    this->waveBuffer.Reset();
    this->waveBuffer.PushZeros(this->polyphase.GetLatency());
    this->subwoofer.SetSpeaker(this->samplingRate, this->speaker);
    this->subwoofer.SetBassGain(this->samplingRate, this->bassFactor);
    this->biquad[0].Reset();
    this->biquad[1].Reset();
    this->biquad[0].SetLowPassParameter((float) this->speaker, this->samplingRate, 0.707f);
    this->biquad[1].SetLowPassParameter((float) this->speaker, this->samplingRate, 0.707f);
    this->samplingRatePeriod = 1.0f / (float) this->samplingRate;
    this->antiPop = 0.0f;
}

void ViPERBass::SetBassFactor(float bassFactor) {
    if (this->bassFactor != bassFactor) {
        this->bassFactor = bassFactor;
        this->subwoofer.SetSpeaker(this->samplingRate, this->speaker);
        this->subwoofer.SetBassGain(this->samplingRate, this->bassFactor);
    }
}

void ViPERBass::SetEnable(bool enable) {
    if (this->enable != enable) {
        if (enable) Reset();
        this->enable = enable;
    }
}

void ViPERBass::SetProcessMode(ProcessMode processMode) {
    if (this->processMode != processMode) {
        this->processMode = processMode;
        Reset();
    }
}

void ViPERBass::SetSamplingRate(uint32_t samplingRate) {
    if (this->samplingRate != samplingRate) {
        this->samplingRate = samplingRate;
        this->samplingRatePeriod = 1.0f / (float) samplingRate;
        this->polyphase.SetSamplingRate(this->samplingRate);
        this->biquad[0].SetLowPassParameter((float) this->speaker, this->samplingRate, 0.707f);
        this->biquad[1].SetLowPassParameter((float) this->speaker, this->samplingRate, 0.707f);
        this->subwoofer.SetSpeaker(this->samplingRate, this->speaker);
        this->subwoofer.SetBassGain(this->samplingRate, this->bassFactor);
    }
}

void ViPERBass::SetSpeaker(uint32_t speaker) {
    if (this->speaker != speaker) {
        this->speaker = speaker;
        this->biquad[0].SetLowPassParameter((float) this->speaker, this->samplingRate, 0.707f);
        this->biquad[1].SetLowPassParameter((float) this->speaker, this->samplingRate, 0.707f);
        this->subwoofer.SetSpeaker(this->samplingRate, this->speaker);
    }
}

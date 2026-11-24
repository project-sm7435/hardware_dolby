#include "Subwoofer.h"
#include "../constants.h"
#include <cmath>

Subwoofer::Subwoofer() {
    uint32_t samplingRate = VIPER_DEFAULT_SAMPLING_RATE;
    this->currentGainDb = 0.0f;
    this->speakerHz = 60;
    this->peak[0].RefreshFilter(MultiBiquad::FilterType::PEAK, 0.0, 37.0, samplingRate, 1.0, false);
    this->peak[1].RefreshFilter(MultiBiquad::FilterType::PEAK, 0.0, 37.0, samplingRate, 1.0, false);
    this->peakLow[0].RefreshFilter(MultiBiquad::FilterType::PEAK, 0.0, 75.0, samplingRate, 1.0, false);
    this->peakLow[1].RefreshFilter(MultiBiquad::FilterType::PEAK, 0.0, 75.0, samplingRate, 1.0, false);
    this->lowpass[0].RefreshFilter(MultiBiquad::FilterType::LOW_PASS, 0.0, 200.0, samplingRate, 1.0, false);
    this->lowpass[1].RefreshFilter(MultiBiquad::FilterType::LOW_PASS, 0.0, 200.0, samplingRate, 1.0, false);
}

void Subwoofer::Process(float *samples, uint32_t size) {
    for (uint32_t i = 0; i < size * 2; i += 2) {
        double dry0 = samples[i];
        double tmp = this->peak[0].ProcessSample(dry0);
        tmp = this->peakLow[0].ProcessSample(tmp);
        tmp = this->lowpass[0].ProcessSample(tmp - dry0);
        samples[i] = static_cast<float>(dry0) + (static_cast<float>(tmp) * 0.6f);

        double dry1 = samples[i + 1];
        tmp = this->peak[1].ProcessSample(dry1);
        tmp = this->peakLow[1].ProcessSample(tmp);
        tmp = this->lowpass[1].ProcessSample(tmp - dry1);
        samples[i + 1] = static_cast<float>(dry1) + (static_cast<float>(tmp) * 0.6f);
    }
}

void Subwoofer::SetBassGain(uint32_t samplingRate, float gainDb) {
    this->currentGainDb = gainDb;
    float clampedGain = gainDb > 0.0f ? gainDb : 0.0f;
    float gain = clampedGain * 4.0f;
    if (gain > 14.0f) gain = 14.0f;
    float gainLower = gain * 0.35f;

    float centerFreq = static_cast<float>(this->speakerHz);
    if (centerFreq < 15.0f) centerFreq = 15.0f;
    float maxFreq = static_cast<float>(samplingRate) * 0.45f;
    float upperFreq = centerFreq * 1.6f;
    if (upperFreq > maxFreq) upperFreq = maxFreq;
    float cutoffFreq = centerFreq * 2.2f;
    if (cutoffFreq < 90.0f) cutoffFreq = 90.0f;
    if (cutoffFreq > maxFreq) cutoffFreq = maxFreq;

    this->peak[0].RefreshFilter(MultiBiquad::FilterType::PEAK, gain, centerFreq, samplingRate, 0.8f, true);
    this->peak[1].RefreshFilter(MultiBiquad::FilterType::PEAK, gain, centerFreq, samplingRate, 0.8f, true);
    this->peakLow[0].RefreshFilter(MultiBiquad::FilterType::PEAK, gainLower, upperFreq, samplingRate, 0.6f, true);
    this->peakLow[1].RefreshFilter(MultiBiquad::FilterType::PEAK, gainLower, upperFreq, samplingRate, 0.6f, true);
    this->lowpass[0].RefreshFilter(MultiBiquad::FilterType::LOW_PASS, 0.0f, cutoffFreq, samplingRate, 0.707f, false);
    this->lowpass[1].RefreshFilter(MultiBiquad::FilterType::LOW_PASS, 0.0f, cutoffFreq, samplingRate, 0.707f, false);
}

void Subwoofer::SetSpeaker(uint32_t samplingRate, uint32_t speakerHz) {
    this->speakerHz = speakerHz;
    SetBassGain(samplingRate, this->currentGainDb);
}

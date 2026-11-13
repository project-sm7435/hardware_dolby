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
        double tmp;

        tmp = this->peak[0].ProcessSample(samples[i]);
        tmp = this->peakLow[0].ProcessSample(tmp);
        tmp = this->lowpass[0].ProcessSample(tmp - samples[i]);
        samples[i] = (samples[i] * 0.5f) + ((float) tmp * 0.6f);

        tmp = this->peak[1].ProcessSample(samples[i + 1]);
        tmp = this->peakLow[1].ProcessSample(tmp);
        tmp = this->lowpass[1].ProcessSample(tmp - samples[i + 1]);
        samples[i + 1] = (samples[i + 1] * 0.5f) + ((float) tmp * 0.6f);
    }
}

void Subwoofer::SetBassGain(uint32_t samplingRate, float gainDb) {
    this->currentGainDb = gainDb;
    float safeGain = gainDb > 0.001f ? gainDb : 0.001f;
    float gain = 20.0f * log10(safeGain);
    float gainLower = 20.0f * log10(safeGain / 8.0f);

    // Scale sub-bass peak around selected speaker cutoff Hz (default 60 Hz -> 44 Hz primary, 80 Hz upper, 380 Hz LPF)
    float centerFreq = (float) this->speakerHz * (44.0f / 60.0f);
    if (centerFreq < 15.0f) centerFreq = 15.0f;
    float upperFreq = centerFreq * 1.82f;
    float cutoffFreq = (float) this->speakerHz * (380.0f / 60.0f);
    if (cutoffFreq < 120.0f) cutoffFreq = 120.0f;

    this->peak[0].RefreshFilter(MultiBiquad::FilterType::PEAK, gain, centerFreq, samplingRate, 0.75, true);
    this->peak[1].RefreshFilter(MultiBiquad::FilterType::PEAK, gain, centerFreq, samplingRate, 0.75, true);
    this->peakLow[0].RefreshFilter(MultiBiquad::FilterType::PEAK, gainLower, upperFreq, samplingRate, 0.2, true);
    this->peakLow[1].RefreshFilter(MultiBiquad::FilterType::PEAK, gainLower, upperFreq, samplingRate, 0.2, true);
    this->lowpass[0].RefreshFilter(MultiBiquad::FilterType::LOW_PASS, 0.0, cutoffFreq, samplingRate, 0.6, false);
    this->lowpass[1].RefreshFilter(MultiBiquad::FilterType::LOW_PASS, 0.0, cutoffFreq, samplingRate, 0.6, false);
}

void Subwoofer::SetSpeaker(uint32_t samplingRate, uint32_t speakerHz) {
    this->speakerHz = speakerHz;
    SetBassGain(samplingRate, this->currentGainDb);
}

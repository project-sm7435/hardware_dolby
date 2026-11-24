#include "TimeConstDelay.h"

TimeConstDelay::TimeConstDelay() {
    this->offset = 0;
    this->sampleCount = 0;
}

float TimeConstDelay::ProcessSample(float sample) {
    if (this->sampleCount == 0 || this->samples.empty()) {
        return sample;
    }
    float val = this->samples[this->offset];
    this->samples[this->offset] = sample;
    this->offset = (this->offset + 1) % this->sampleCount;
    return val;
}

void TimeConstDelay::SetParameters(uint32_t samplingRate, float delay) {
    this->sampleCount = (uint32_t) ((float) samplingRate * delay);
    this->samples.assign(this->sampleCount, 0.0f);
    this->offset = 0;
}

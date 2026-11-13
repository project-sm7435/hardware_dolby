#pragma once

#include <cstdint>
#include "MultiBiquad.h"

class Subwoofer {
public:
    Subwoofer();

    void Process(float *samples, uint32_t size);
    void SetBassGain(uint32_t samplingRate, float gainDb);
    void SetSpeaker(uint32_t samplingRate, uint32_t speakerHz);

private:
    MultiBiquad peak[2];
    MultiBiquad peakLow[2];
    MultiBiquad lowpass[2];
    float currentGainDb;
    uint32_t speakerHz;
};

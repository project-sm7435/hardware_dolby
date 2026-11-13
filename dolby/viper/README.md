# ViPER effects used by Dolby Advanced Settings

Only the effects the Dolby app uses are kept here, copied unchanged from the ViPER FX RE source:

| Advanced Settings row | Engine effect (`effects/`) | Helper code (`utils/`) |
|---|---|---|
| ViPER bass | ViPERBass | Biquad, Polyphase, Subwoofer, WaveBuffer |
| ViPER clarity | ViPERClarity | HiFi, HighShelf, NoiseSharpening |
| Dynamic system / dynamic bass | DynamicSystem | DynamicBass (+ MultiBiquad, FIR, IIR filters) |

## Flow

Dolby Advanced Settings (`ModernAdvancedSettingsScreen.kt`)
-> `ViperEffectModel.kt` (slider value -> engine value)
-> `ViperController.kt` (command id + value, sent to the effect)
-> this folder, built into `libv4a_re.so` (Effects + Utils)
-> DSP engine (`ViPER::process`)
-> audio output

Kotlin cannot run C++ directly. The app sends commands to an AudioEffect, and this folder is the
source of the native side. `Android.bp` builds it as `libdolby_viper_jni` (effects, utils and
`viper_dsp_engine.cpp`, which also exports the AudioEffect library entry point).

## Notes

- The effect UUID in `viper_dsp_engine.cpp` equals `DolbyConstants.VIPER_EFFECT_UUID`. If the ROM also
  ships the vendor `libv4a_re.so` with the same UUID, register only one of them in `audio_effects.xml`.
- The effect processes stereo only. Other channel counts are passed through untouched.
- `ViPERBass` PURE_BASS_PLUS indexes its wave buffer with a frame offset; check it against the
  upstream ViPER source if the bass sounds out of phase.

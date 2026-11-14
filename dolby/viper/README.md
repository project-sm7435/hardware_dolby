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

Kotlin cannot run C++ directly. The app sends commands to the compiled effect library, so this folder
is the source that library is built from. It is not compiled by `Android.bp` yet.

## Still missing to build it

`ViPER.cpp` / `ViPER.h` (the engine that owns these effects) include `../ViPER4Android.h` and need the
Android effect wrapper (`AUDIO_EFFECT_LIBRARY_INFO_SYM`). Neither was in the zip provided.

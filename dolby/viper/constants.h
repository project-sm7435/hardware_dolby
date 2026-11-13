#pragma once

#ifdef ANDROID_TOOLCHAIN
#include <android/errno.h>
#include <android/log.h>
#else
#include <cerrno>
#endif

#if defined(__arm__)
#define VIPER_ARCHITECTURE "ARM"
#elif defined(__aarch64__)
#define VIPER_ARCHITECTURE "ARM64"
#elif defined(__i386__)
#define VIPER_ARCHITECTURE "x86"
#elif defined(__x86_64__)
#define VIPER_ARCHITECTURE "x86_64"
#else
#define VIPER_ARCHITECTURE "GENERIC"
#endif

#define VIPER_NAME "ViPER4Android"
#define VIPER_AUTHORS "viper.WYF, Martmists, Iscle"
#define VIPER_DEFAULT_SAMPLING_RATE 44100

#ifndef VIPER_LOGI
#ifdef ANDROID_TOOLCHAIN
#define VIPER_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ViPER_DSP", __VA_ARGS__)
#define VIPER_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ViPER_DSP", __VA_ARGS__)
#else
#define VIPER_LOGI(...)
#define VIPER_LOGE(...)
#endif
#endif

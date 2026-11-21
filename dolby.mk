#
# Copyright (C) 2022 FlamingoOS Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Dolby path
DOLBY_PATH := hardware/dolby

# Soong Namespace
PRODUCT_SOONG_NAMESPACES += \
    $(DOLBY_PATH)

# Enable codec support
AUDIO_FEATURE_ENABLED_DS2_DOLBY_DAP := true

# SEPolicy
BOARD_VENDOR_SEPOLICY_DIRS += $(DOLBY_PATH)/sepolicy/vendor

# AIDL VINTF
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += $(DOLBY_PATH)/dolby_framework_matrix.xml

PRODUCT_PACKAGES += \
    dms-service.xml \
    vendor.dolby.media.c2-default-service-dax.xml

# Configs
PRODUCT_COPY_FILES += \
    $(DOLBY_PATH)/configs/dax-default.xml:$(TARGET_COPY_OUT_VENDOR)/etc/dolby/dax-default.xml \
    $(DOLBY_PATH)/configs/media_codecs_dolby_audio.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_dolby_audio.xml

# Dolby Shim
PRODUCT_PACKAGES += \
    libshim_dolby

# Init
PRODUCT_PACKAGES += \
    init.dolby.rc

# Overlays
PRODUCT_PACKAGES += \
    DolbyFrameworksResCommon

# Dolby Spatial Audio
PRODUCT_COPY_FILES += \
    $(DOLBY_PATH)/configs/android.hardware.sensor.dynamic.head_tracker.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.sensor.dynamic.head_tracker.xml

# Dolby Spatial Audio: optimize spatializer effect
PRODUCT_PROPERTY_OVERRIDES += \
    audio.spatializer.effect.util_clamp_min=300

# Dolby Spatial Audio: declare use of spatial audio
PRODUCT_PROPERTY_OVERRIDES += \
    ro.audio.spatializer_enabled=true \
    ro.audio.stereo_spatialization_enabled=true \
    ro.audio.headtracking_enabled=true \
    ro.audio.spatializer_transaural_enabled_default=false \
    persist.vendor.audio.spatializer.speaker_enabled=true

# Media (C2)
PRODUCT_PACKAGES += \
    android.hardware.media.c2@1.0.vendor \
    android.hardware.media.c2@1.1.vendor \
    android.hardware.media.c2@1.2.vendor \
    libcodec2_hidl@1.2.vendor \
    libsfplugin_ccodec_utils.vendor \
    libcodec2_soft_common.vendor

# AIDL Audio & Codec2 HAL Selection
PRODUCT_VENDOR_PROPERTIES += \
    vendor.audio.hal.aidl.enabled=true \
    media.c2.hal.selection=aidl

# Codec2 Props
PRODUCT_VENDOR_PROPERTIES += \
    vendor.audio.c2.preferred=true \
    debug.c2.use_dmabufheaps=1 \
    vendor.qc2audio.suspend.enabled=true \
    vendor.qc2audio.per_frame.flac.dec.enabled=true

# Dolby Props
PRODUCT_VENDOR_PROPERTIES += \
    ro.vendor.dolby.dax.version=DAX3_3.12.0.8_r1 \
    ro.vendor.audio.dolby.dax.version=DAX3_3.12.0.8_r1 \
    ro.vendor.dolby.dax.support=true \
    ro.vendor.audio.dolby.dax.game.enable=true \
    vendor.audio.dolby.ds2.hardbypass=false \
    vendor.audio.dolby.ds2.enabled=false

# Remove Packages for Dolby Support
PRODUCT_PACKAGES += \
    RemovePackagesDolby

# DSP Volume Synchronizer
PRODUCT_PACKAGES += \
    DSPVolumeSynchronizer

# XiaomiDolby
PRODUCT_PACKAGES += \
    XiaomiDolby

# Dolby AIDL DMS Proprietary blobs
PRODUCT_COPY_FILES += \
    $(DOLBY_PATH)/proprietary/vendor/etc/init/dms-service.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/dms-service.rc

PRODUCT_PACKAGES += \
    libdapparamstorage \
    libdlbdsservice \
    libdlbpreg \
    libdmshal \
    liboem_specific \
    vendor.dolby.dms-V1-ndk \
    vendor.dolby.hardware.dms@2.0 \
    vendor.dolby.dms.service

# Codec2 (Dolby)
PRODUCT_COPY_FILES += \
    $(DOLBY_PATH)/proprietary/vendor/etc/init/vendor.dolby.media.c2-default-service-dax.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/vendor.dolby.media.c2-default-service-dax.rc

PRODUCT_PACKAGES += \
    libcodec2_soft_ac4dec \
    libcodec2_soft_ddpdec \
    libcodec2_store_dolby \
    libdeccfg \
    vendor.dolby.media.c2-default-service-dax

# Dolby AIDL SoundFX Blobs + ViPER4Android
PRODUCT_PACKAGES += \
    libaudio_aidl_conversion_common_ndk \
    libdlbvolaidl \
    libhwdapaidl \
    libswgamedapaidl \
    libswspatializeraidl \
    libswspatializeraidl_ext \
    libspatializerparamstorage \
    libv4a_re
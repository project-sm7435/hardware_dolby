/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data.autoeq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AutoEqCache(context: Context) {
    private val baseDir = File(context.filesDir, "autoeq").apply { mkdirs() }
    private val profilesDir = File(baseDir, "profiles").apply { mkdirs() }

    // IDs come from a remote index: never let them leave the cache folder.
    private fun safeId(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_").replace("..", "_")

    private fun <T> readOrDrop(file: File, parse: (String) -> T): T? {
        if (!file.exists()) return null
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            file.delete() // corrupt cache: drop it so it is downloaded again
            null
        }
    }

    suspend fun saveMetadata(metadata: AutoEqMetadata) = withContext(Dispatchers.IO) {
        File(baseDir, "metadata.json").writeText(AutoEqMetadata.toJson(metadata))
    }

    suspend fun loadMetadata(): AutoEqMetadata? = withContext(Dispatchers.IO) {
        readOrDrop(File(baseDir, "metadata.json")) { AutoEqMetadata.fromJson(it) }
    }

    suspend fun saveIndex(index: AutoEqIndex) = withContext(Dispatchers.IO) {
        File(baseDir, "index.json").writeText(AutoEqIndex.toJson(index))
    }

    suspend fun loadIndex(): AutoEqIndex? = withContext(Dispatchers.IO) {
        readOrDrop(File(baseDir, "index.json")) { AutoEqIndex.fromJson(it) }
    }

    suspend fun saveProfile(profile: AutoEqProfile) = withContext(Dispatchers.IO) {
        File(profilesDir, "${safeId(profile.id)}.json").writeText(AutoEqProfile.toJson(profile))
    }

    suspend fun loadProfile(id: String): AutoEqProfile? = withContext(Dispatchers.IO) {
        readOrDrop(File(profilesDir, "${safeId(id)}.json")) { AutoEqProfile.fromJson(it) }
    }
}

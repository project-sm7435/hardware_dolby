/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.tile

import android.content.SharedPreferences
import android.content.res.Resources
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.R
import org.lunaris.dolby.data.DolbyRepository

class DolbyTileService : TileService() {

    private val repositoryDelegate = lazy { DolbyRepository(applicationContext) }
    private val repository by repositoryDelegate

    // Keeps the tile in step with the card in the app while the QS panel is open.
    private val enabledListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DolbyConstants.PREF_ENABLE) updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        repository.registerEnabledListener(enabledListener)
        updateTile()
    }

    override fun onStopListening() {
        repository.unregisterEnabledListener(enabledListener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Same path as the main card, based on the saved switch (the live effect can lag after boot).
        repository.setDolbyEnabledFromUser(!repository.getSavedDolbyEnabled())
        updateTile()
    }

    override fun onDestroy() {
        if (repositoryDelegate.isInitialized()) repository.close()
        super.onDestroy()
    }

    private fun updateTile() {
        qsTile?.apply {
            val enabled = repository.getSavedDolbyEnabled()
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = getProfileName()
            updateTile()
        }
    }

    private fun getProfileName(): String {
        val profile = repository.getCurrentProfile()
        val profiles = resources.getStringArray(R.array.dolby_profile_entries)
        val profileValues = resources.getStringArray(R.array.dolby_profile_values)
        
        return try {
            val index = profileValues.indexOf(profile.toString())
            if (index != -1) profiles[index] else getString(R.string.dolby_unknown)
        } catch (e: Exception) {
            getString(R.string.dolby_unknown)
        }
    }
}

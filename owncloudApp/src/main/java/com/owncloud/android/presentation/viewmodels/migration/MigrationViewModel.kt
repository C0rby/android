/**
 * ownCloud Android client application
 *
 * @author David González Verdugo
 * @author Abel García de Prada
 * Copyright (C) 2020 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.presentation.viewmodels.migration

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.owncloud.android.data.preferences.datasources.SharedPreferencesProvider
import com.owncloud.android.data.storage.LocalStorageProvider
import com.owncloud.android.datamodel.OCUpload
import com.owncloud.android.datamodel.UploadsStorageManager
import com.owncloud.android.domain.utils.Event
import com.owncloud.android.presentation.ui.migration.StorageMigrationActivity.Companion.PREFERENCE_ALREADY_MIGRATED_TO_SCOPED_STORAGE
import com.owncloud.android.providers.CoroutinesDispatcherProvider
import kotlinx.coroutines.launch
import java.io.File

/**
 * View Model to keep a reference to the capability repository and an up-to-date capability
 */
class MigrationViewModel(
    private val rootFolder: String,
    private val scopedStorageProvider: LocalStorageProvider.ScopedStorageProvider,
    private val preferencesProvider: SharedPreferencesProvider,
    private val uploadsStorageManager: UploadsStorageManager,
    private val coroutineDispatcherProvider: CoroutinesDispatcherProvider,
) : ViewModel() {

    private val _migrationState = MediatorLiveData<Event<MigrationState>>()
    val migrationState: LiveData<Event<MigrationState>> = _migrationState

    init {
        _migrationState.postValue(Event(MigrationState.MigrationIntroState))
    }

    private fun getLegacyStorageSizeInBytes(): Long {
        val legacyStorageDirectory = File(LocalStorageProvider.LegacyStorageProvider(rootFolder).getRootFolderPath())
        return scopedStorageProvider.sizeOfDirectory(legacyStorageDirectory)
    }

    fun moveLegacyStorageToScopedStorage() {
        viewModelScope.launch(coroutineDispatcherProvider.io) {
            scopedStorageProvider.moveLegacyToScopedStorage()
            updatePendingUploadsPath()
            moveToNextState()
        }
    }

    fun copyLegacyStorageToScopedStorage() {
        viewModelScope.launch(coroutineDispatcherProvider.io) {
            scopedStorageProvider.copyLegacyToScopedStorage()
            moveToNextState()
        }
    }

    private fun saveAlreadyMigratedPreference() {
        preferencesProvider.putBoolean(key = PREFERENCE_ALREADY_MIGRATED_TO_SCOPED_STORAGE, value = true)
    }

    private fun updatePendingUploadsPath() {
        uploadsStorageManager.clearSuccessfulUploads()
        val storedUploads: Array<OCUpload> = uploadsStorageManager.allStoredUploads
        val uploadsWithUpdatedPath =
            storedUploads.map { it.apply { localPath = scopedStorageProvider.getTemporalPath(it.accountName) + it.remotePath } }
        uploadsWithUpdatedPath.forEach { uploadsStorageManager.updateUpload(it) }
    }

    fun moveToNextState(migrationType: MigrationState.MigrationType = MigrationState.MigrationType.MIGRATE_AND_KEEP) {

        val nextState: MigrationState = when (_migrationState.value?.peekContent()) {
            is MigrationState.MigrationIntroState -> MigrationState.MigrationChoiceState(
                legacyStorageSpaceInBytes = getLegacyStorageSizeInBytes()
            )
            is MigrationState.MigrationChoiceState -> MigrationState.MigrationProgressState(migrationType)
            is MigrationState.MigrationProgressState -> MigrationState.MigrationCompletedState
            is MigrationState.MigrationCompletedState -> MigrationState.MigrationCompletedState
            null -> MigrationState.MigrationIntroState
        }

        if (nextState is MigrationState.MigrationCompletedState) {
            saveAlreadyMigratedPreference()
        }

        _migrationState.postValue(Event(nextState))
    }
}

package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ClickStep
import com.example.data.ProfileEntity
import com.example.data.ProfileRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(private val repository: ProfileRepository) : ViewModel() {

    // Fetch all records reactive flow from room
    val profiles: StateFlow<List<ProfileEntity>> = repository.allProfiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    init {
        // Pre-populate database with cool preset automation profiles on first launch if empty
        viewModelScope.launch {
            repository.allProfiles.collect { list ->
                if (list.isEmpty()) {
                    createPresetProfiles()
                }
            }
        }
    }

    private suspend fun createPresetProfiles() {
        // Preset 1: Basic single tap clicker
        val s1 = listOf(ClickStep("click", 300, 600, 0, 0, 1000L, 50L))
        repository.insert(
            ProfileEntity(
                name = "Standard Continuous Tap",
                type = "single",
                intervalMs = 1000L,
                repeatCount = -1,
                stepsString = ProfileEntity.buildStepsString(s1)
            )
        )

        // Preset 2: Double-click sequence
        val s2 = listOf(
            ClickStep("click", 250, 500, 0, 0, 300L, 50L),
            ClickStep("click", 500, 500, 0, 0, 300L, 50L)
        )
        repository.insert(
            ProfileEntity(
                name = "Dual Target Grinder",
                type = "multi",
                intervalMs = 300L,
                repeatCount = -1,
                stepsString = ProfileEntity.buildStepsString(s2)
            )
        )

        // Preset 3: Glide swipe automation
        val s3 = listOf(
            ClickStep("swipe", 540, 1400, 540, 400, 2000L, 300L)
        )
        repository.insert(
            ProfileEntity(
                name = "Continuous Swipe Feeder",
                type = "swipe",
                intervalMs = 2500L,
                repeatCount = -1,
                stepsString = ProfileEntity.buildStepsString(s3)
            )
        )
    }

    fun addNewProfile(name: String, type: String, interval: Long, repeatCount: Int) {
        viewModelScope.launch {
            val defaultSteps = when (type) {
                "single" -> listOf(ClickStep("click", 300, 600, 0, 0, interval, 50L))
                "multi" -> listOf(
                    ClickStep("click", 250, 500, 0, 0, interval, 50L),
                    ClickStep("click", 450, 700, 0, 0, interval, 50L)
                )
                "swipe" -> listOf(
                    ClickStep("swipe", 300, 1000, 300, 400, interval, 300L)
                )
                else -> emptyList()
            }
            
            repository.insert(
                ProfileEntity(
                    name = name.ifBlank { "Unidentified Profile" },
                    type = type,
                    intervalMs = interval,
                    repeatCount = repeatCount,
                    stepsString = ProfileEntity.buildStepsString(defaultSteps)
                )
            )
        }
    }

    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            repository.delete(profile)
        }
    }
}

package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val type: String, // "single", "multi", "swipe"
    val intervalMs: Long = 1000L,
    val repeatCount: Int = -1, // -1 means infinite, >=1 is exact repetitions
    val stepsString: String = "" // formatted steps: "type,startX,startY,endX,endY,delay,duration;..."
) {
    // Helper to parse steps
    fun getSteps(): List<ClickStep> {
        if (stepsString.isBlank()) return emptyList()
        return stepsString.split(";").mapNotNull { stepStr ->
            val parts = stepStr.split(",")
            if (parts.size >= 7) {
                ClickStep(
                    type = parts[0],
                    startX = parts[1].toIntOrNull() ?: 0,
                    startY = parts[2].toIntOrNull() ?: 0,
                    endX = parts[3].toIntOrNull() ?: 0,
                    endY = parts[4].toIntOrNull() ?: 0,
                    delay = parts[5].toLongOrNull() ?: 1000L,
                    duration = parts[6].toLongOrNull() ?: 50L
                )
            } else null
        }
    }

    companion object {
        fun buildStepsString(steps: List<ClickStep>): String {
            return steps.joinToString(";") { step ->
                "${step.type},${step.startX},${step.startY},${step.endX},${step.endY},${step.delay},${step.duration}"
            }
        }
    }
}

data class ClickStep(
    val type: String, // "click" or "swipe"
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val delay: Long,
    val duration: Long
)

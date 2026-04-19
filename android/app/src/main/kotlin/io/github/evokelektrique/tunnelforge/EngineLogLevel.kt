package io.github.evokelektrique.tunnelforge

import android.util.Log

enum class EngineLogLevel(val wireValue: String) {
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    DEBUG("debug"),
    ;

    fun includesPriority(priority: Int): Boolean {
        val level = fromAndroidPriority(priority)
        return when (this) {
            INFO -> level == INFO || level == WARNING || level == ERROR
            WARNING -> level == WARNING || level == ERROR
            ERROR -> level == ERROR
            DEBUG -> true
        }
    }

    companion object {
        fun fromWireValue(raw: String?): EngineLogLevel =
            when (raw?.trim()?.lowercase()) {
                "info" -> INFO
                "warning" -> WARNING
                "debug" -> DEBUG
                else -> ERROR
            }

        fun fromAndroidPriority(priority: Int): EngineLogLevel =
            when {
                priority <= Log.DEBUG -> DEBUG
                priority == Log.INFO -> INFO
                priority == Log.WARN -> WARNING
                else -> ERROR
            }
    }
}

object EngineLogPolicy {
    @Volatile
    private var level: EngineLogLevel = EngineLogLevel.ERROR

    fun currentLevel(): EngineLogLevel = level

    fun update(level: EngineLogLevel) {
        this.level = level
    }

    fun shouldForward(priority: Int): Boolean = level.includesPriority(priority)
}

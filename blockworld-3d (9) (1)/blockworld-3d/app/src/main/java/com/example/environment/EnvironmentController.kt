package com.example.environment

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object EnvironmentController {

    private var initialized = false

    private var observableSettings by mutableStateOf(
        EnvironmentSettings()
    )

    val current: EnvironmentSettings
        get() = observableSettings

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return

        observableSettings = EnvironmentStorage.load(
            context.applicationContext
        )

        initialized = true
    }

    fun snapshot(): EnvironmentSettings {
        return observableSettings
    }

    fun update(
        context: Context,
        transform: (EnvironmentSettings) -> EnvironmentSettings
    ) {
        val updated = transform(observableSettings).clamped()

        observableSettings = updated

        EnvironmentStorage.save(
            context.applicationContext,
            updated
        )
    }

    fun reset(context: Context) {
        val defaults = EnvironmentSettings()

        observableSettings = defaults

        EnvironmentStorage.save(
            context.applicationContext,
            defaults
        )
    }
}
package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable object RecordRoute
@Serializable object LibraryRoute
@Serializable object AnalyzeRoute
@Serializable object SpiritBoxRoute
@Serializable object SettingsRoute
@Serializable data class EditorRoute(val recordingId: Long)
@Serializable data class ConverterRoute(val recordingIdsCsv: String)

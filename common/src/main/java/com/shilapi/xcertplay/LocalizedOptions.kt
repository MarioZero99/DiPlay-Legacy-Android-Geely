package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.CarPlaySize
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

internal fun CarPlaySize.localizedLabel(context: Context): String = context.getString(when (this) {
    CarPlaySize.LARGE -> R.string.option_size_large
    CarPlaySize.MEDIUM -> R.string.option_size_medium
    CarPlaySize.SMALL -> R.string.option_size_small
})

internal fun DiLink51ClusterLayout.Theme.localizedLabel(context: Context): String = context.getString(when (this) {
    DiLink51ClusterLayout.Theme.SCENARIO -> R.string.option_theme_scenario
    DiLink51ClusterLayout.Theme.MAP -> R.string.option_theme_map
    DiLink51ClusterLayout.Theme.SIMPLE -> R.string.option_theme_simple
})

internal fun DiLink51ClusterLayout.Contrast.localizedLabel(context: Context): String = context.getString(when (this) {
    DiLink51ClusterLayout.Contrast.DEFAULT -> R.string.option_contrast_default
    DiLink51ClusterLayout.Contrast.LIGHT -> R.string.option_contrast_light
    DiLink51ClusterLayout.Contrast.DARK -> R.string.option_contrast_dark
})

internal fun ManualHotspotValidation.Error.messageResource(): Int = when (this) {
    ManualHotspotValidation.Error.EMPTY_NAME -> R.string.hotspot_error_empty_name
    ManualHotspotValidation.Error.LONG_NAME -> R.string.hotspot_error_long_name
    ManualHotspotValidation.Error.INVALID_CHARACTER -> R.string.hotspot_error_invalid_character
    ManualHotspotValidation.Error.PASSWORD_LENGTH -> R.string.hotspot_error_password_length
}

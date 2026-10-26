package app.routine.feature.templates

import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet

/**
 * Self-contained catalog of 20 researched everyday Android automation templates (`tpl_21`..`tpl_40`)
 * covering state restoration, workplace/commute profiles, Private DNS & USB port security,
 * Digital Wellbeing grayscale/theme schedules, e-book/camera focus, and battery/alarm routines.
 */
object ResearchedTemplateCatalog {
    fun providers(): Set<TemplateProvider> = providersTwentyOneToTwentyFive() +
        providersTwentySixToThirty() +
        providersThirtyOneToThirtyFive() +
        providersThirtySixToForty()

    private fun providersTwentyOneToTwentyFive(): Set<TemplateProvider> = setOf(
        StaticTemplateProvider(
            order = 21,
            id = "tpl_21_morning_unplug_reset",
            titleRes = R.string.tpl_21_title,
            descRes = R.string.tpl_21_desc,
            iconName = "wb_sunny",
            trigger = Trigger(TriggerType.CHARGER, mapOf("state" to "DISCONNECTED")),
            actions = listOf(
                Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "false")),
                Action(ActionType.RINGER_MODE, mapOf("mode" to "NORMAL")),
                Action(ActionType.BRIGHTNESS, mapOf("percent" to "65")),
            ),
            cooldownSeconds = 300L,
        ),
        StaticTemplateProvider(
            order = 22,
            id = "tpl_22_wifi_on_morning",
            titleRes = R.string.tpl_22_title,
            descRes = R.string.tpl_22_desc,
            iconName = "alarm_on",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "7", "minute" to "0")),
            actions = listOf(Action(ActionType.WIFI_TOGGLE, mapOf("enabled" to "true"))),
        ),
        StaticTemplateProvider(
            order = 23,
            id = "tpl_23_night_light_off_morning",
            titleRes = R.string.tpl_23_title,
            descRes = R.string.tpl_23_desc,
            iconName = "wb_sunny",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "7", "minute" to "0")),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SECURE", "key" to "night_display_activated", "value" to "0"),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 24,
            id = "tpl_24_restore_leaving_venue",
            titleRes = R.string.tpl_24_title,
            descRes = R.string.tpl_24_desc,
            iconName = "home",
            trigger = Trigger(
                TriggerType.LOCATION_GEOFENCE,
                mapOf(
                    "latitude" to "24.8607",
                    "longitude" to "67.0011",
                    "radiusMeters" to "150",
                    "transition" to "EXIT",
                ),
            ),
            actions = listOf(
                Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "false")),
                Action(ActionType.RINGER_MODE, mapOf("mode" to "NORMAL")),
            ),
            cooldownSeconds = 120L,
        ),
        StaticTemplateProvider(
            order = 25,
            id = "tpl_25_work_wifi_vibrate",
            titleRes = R.string.tpl_25_title,
            descRes = R.string.tpl_25_desc,
            iconName = "work",
            trigger = Trigger(
                TriggerType.WIFI_SSID,
                mapOf("ssid" to "OfficeWiFi", "transition" to "CONNECTED"),
            ),
            actions = listOf(Action(ActionType.RINGER_MODE, mapOf("mode" to "VIBRATE"))),
            cooldownSeconds = 300L,
        ),
    )

    private fun providersTwentySixToThirty(): Set<TemplateProvider> = setOf(
        StaticTemplateProvider(
            order = 26,
            id = "tpl_26_unwind_leaving_work",
            titleRes = R.string.tpl_26_title,
            descRes = R.string.tpl_26_desc,
            iconName = "work",
            trigger = Trigger(
                TriggerType.WIFI_SSID,
                mapOf("ssid" to "OfficeWiFi", "transition" to "DISCONNECTED"),
            ),
            actions = listOf(
                Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "false")),
                Action(ActionType.RINGER_MODE, mapOf("mode" to "NORMAL")),
            ),
            cooldownSeconds = 300L,
        ),
        StaticTemplateProvider(
            order = 27,
            id = "tpl_27_arrive_home_comfort",
            titleRes = R.string.tpl_27_title,
            descRes = R.string.tpl_27_desc,
            iconName = "home",
            trigger = Trigger(
                TriggerType.WIFI_SSID,
                mapOf("ssid" to "HomeWiFi", "transition" to "CONNECTED"),
            ),
            actions = listOf(
                Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "false")),
                Action(ActionType.RINGER_MODE, mapOf("mode" to "NORMAL")),
                Action(ActionType.ROTATION_LOCK, mapOf("locked" to "false")),
            ),
            cooldownSeconds = 300L,
        ),
        StaticTemplateProvider(
            order = 28,
            id = "tpl_28_car_navigation_cockpit",
            titleRes = R.string.tpl_28_title,
            descRes = R.string.tpl_28_desc,
            iconName = "directions_car",
            trigger = Trigger(TriggerType.BLUETOOTH_CONNECTED, mapOf("deviceCategory" to "CAR")),
            actions = listOf(
                Action(ActionType.BRIGHTNESS, mapOf("percent" to "85")),
                Action(ActionType.ROTATION_LOCK, mapOf("locked" to "false")),
                Action(ActionType.OPEN_URL, mapOf("url" to "geo:0,0")),
            ),
            cooldownSeconds = 180L,
        ),
        StaticTemplateProvider(
            order = 29,
            id = "tpl_29_private_dns_away",
            titleRes = R.string.tpl_29_title,
            descRes = R.string.tpl_29_desc,
            iconName = "wifi_off",
            trigger = Trigger(
                TriggerType.WIFI_SSID,
                mapOf("ssid" to "HomeWiFi", "transition" to "DISCONNECTED"),
            ),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "GLOBAL", "key" to "private_dns_mode", "value" to "hostname"),
                ),
            ),
            cooldownSeconds = 120L,
        ),
        StaticTemplateProvider(
            order = 30,
            id = "tpl_30_local_dns_home_wifi",
            titleRes = R.string.tpl_30_title,
            descRes = R.string.tpl_30_desc,
            iconName = "home",
            trigger = Trigger(
                TriggerType.WIFI_SSID,
                mapOf("ssid" to "HomeWiFi", "transition" to "CONNECTED"),
            ),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "GLOBAL", "key" to "private_dns_mode", "value" to "opportunistic"),
                ),
            ),
            cooldownSeconds = 120L,
        ),
    )

    private fun providersThirtyOneToThirtyFive(): Set<TemplateProvider> = setOf(
        StaticTemplateProvider(
            order = 31,
            id = "tpl_31_usb_debug_lock_charger",
            titleRes = R.string.tpl_31_title,
            descRes = R.string.tpl_31_desc,
            iconName = "battery_charging_full",
            trigger = Trigger(TriggerType.CHARGER, mapOf("state" to "CONNECTED")),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "GLOBAL", "key" to "adb_enabled", "value" to "0"),
                ),
            ),
            cooldownSeconds = 120L,
        ),
        StaticTemplateProvider(
            order = 32,
            id = "tpl_32_post_reboot_check",
            titleRes = R.string.tpl_32_title,
            descRes = R.string.tpl_32_desc,
            iconName = "auto_awesome",
            trigger = Trigger(TriggerType.BOOT_COMPLETED),
            actions = listOf(
                Action(
                    ActionType.NOTIFY,
                    mapOf(
                        "title" to "Routine Active",
                        "message" to "Scheduled automations re-armed after device restart.",
                        "includeLocalBriefing" to "true",
                    ),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 33,
            id = "tpl_33_grayscale_social_apps",
            titleRes = R.string.tpl_33_title,
            descRes = R.string.tpl_33_desc,
            iconName = "nightlight",
            trigger = Trigger(
                TriggerType.APP_OPENED,
                mapOf(
                    "packageNames" to
                        "com.instagram.android,com.zhiliaoapp.musically,com.twitter.android,com.reddit.frontpage",
                ),
            ),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SECURE", "key" to "accessibility_display_daltonizer", "value" to "0"),
                ),
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SECURE", "key" to "accessibility_display_daltonizer_enabled", "value" to "1"),
                ),
            ),
            cooldownSeconds = 120L,
        ),
        StaticTemplateProvider(
            order = 34,
            id = "tpl_34_color_restore_morning",
            titleRes = R.string.tpl_34_title,
            descRes = R.string.tpl_34_desc,
            iconName = "wb_sunny",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "7", "minute" to "0")),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SECURE", "key" to "accessibility_display_daltonizer_enabled", "value" to "0"),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 35,
            id = "tpl_35_dark_theme_evening",
            titleRes = R.string.tpl_35_title,
            descRes = R.string.tpl_35_desc,
            iconName = "bedtime",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "20", "minute" to "0")),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SECURE", "key" to "ui_night_mode", "value" to "2"),
                ),
            ),
        ),
    )

    private fun providersThirtySixToForty(): Set<TemplateProvider> = setOf(
        StaticTemplateProvider(
            order = 36,
            id = "tpl_36_light_theme_morning",
            titleRes = R.string.tpl_36_title,
            descRes = R.string.tpl_36_desc,
            iconName = "wb_sunny",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "7", "minute" to "0")),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SECURE", "key" to "ui_night_mode", "value" to "1"),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 37,
            id = "tpl_37_ebook_reading_focus",
            titleRes = R.string.tpl_37_title,
            descRes = R.string.tpl_37_desc,
            iconName = "bedtime",
            trigger = Trigger(
                TriggerType.APP_OPENED,
                mapOf(
                    "packageNames" to
                        "org.koreader.launcher,com.foobnix.pro.pdf.reader,org.readera,com.amazon.kindle",
                ),
            ),
            actions = listOf(
                Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "true")),
                Action(ActionType.ROTATION_LOCK, mapOf("locked" to "true")),
                Action(ActionType.BRIGHTNESS, mapOf("percent" to "35")),
            ),
            cooldownSeconds = 180L,
        ),
        StaticTemplateProvider(
            order = 38,
            id = "tpl_38_camera_outdoor_boost",
            titleRes = R.string.tpl_38_title,
            descRes = R.string.tpl_38_desc,
            iconName = "photo_library",
            trigger = Trigger(
                TriggerType.APP_OPENED,
                mapOf(
                    "packageNames" to "app.grapheneos.camera,org.fossify.camera," +
                        "net.sourceforge.opencamera,com.android.camera2,com.google.android.GoogleCamera",
                ),
            ),
            actions = listOf(
                Action(ActionType.BRIGHTNESS, mapOf("percent" to "90")),
                Action(ActionType.ROTATION_LOCK, mapOf("locked" to "false")),
            ),
            cooldownSeconds = 120L,
        ),
        StaticTemplateProvider(
            order = 39,
            id = "tpl_39_critical_battery_10",
            titleRes = R.string.tpl_39_title,
            descRes = R.string.tpl_39_desc,
            iconName = "battery_saver",
            trigger = Trigger(
                TriggerType.BATTERY_LEVEL,
                mapOf("thresholdPercent" to "10", "direction" to "BELOW"),
            ),
            actions = listOf(
                Action(ActionType.BRIGHTNESS, mapOf("percent" to "10")),
                Action(ActionType.RINGER_MODE, mapOf("mode" to "VIBRATE")),
                Action(ActionType.BATTERY_SAVER, mapOf("enabled" to "true")),
            ),
            cooldownSeconds = 1800L,
        ),
        StaticTemplateProvider(
            order = 40,
            id = "tpl_40_wakeup_music_alarm",
            titleRes = R.string.tpl_40_title,
            descRes = R.string.tpl_40_desc,
            iconName = "headphones",
            trigger = Trigger(TriggerType.ALARM_DISMISSED),
            actions = listOf(Action(ActionType.OPEN_APP, mapOf("category" to "MUSIC"))),
        ),
    )
}

/**
 * Self-contained Hilt multibinding contributing [ResearchedTemplateCatalog] into Routine's template grid
 * with zero changes to `:core:engine` or `:feature:templates` UI code.
 */
@Module
@InstallIn(SingletonComponent::class)
object ResearchedTemplatesModule {
    @Provides
    @ElementsIntoSet
    fun provideResearchedTemplates(): Set<TemplateProvider> = ResearchedTemplateCatalog.providers()
}

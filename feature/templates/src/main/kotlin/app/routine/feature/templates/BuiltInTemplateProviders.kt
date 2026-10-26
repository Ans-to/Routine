package app.routine.feature.templates

import android.content.Context
import androidx.annotation.StringRes
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.PrivilegeTier
import app.routine.core.model.Rule
import app.routine.core.model.Template
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet

/**
 * Self-contained template provider contract. Adding a new template requires only registering a new
 * [TemplateProvider] into Hilt's `@ElementsIntoSet` / `@IntoSet` multibinding — zero engine or UI edits.
 */
interface TemplateProvider {
    val order: Int
    fun provide(context: Context): Template
}

private class StaticTemplateProvider(
    override val order: Int,
    private val id: String,
    @StringRes private val titleRes: Int,
    @StringRes private val descRes: Int,
    private val iconName: String,
    private val trigger: Trigger,
    private val actions: List<Action>,
    private val cooldownSeconds: Long = 60L,
    private val explicitTier: PrivilegeTier? = null,
) : TemplateProvider {
    override fun provide(context: Context): Template {
        val title = context.getString(titleRes)
        val desc = context.getString(descRes)
        val prototypeRule = Rule(
            id = "rule_$id",
            name = title,
            trigger = trigger,
            actions = actions,
            enabled = true,
            cooldownSeconds = cooldownSeconds,
            templateId = id,
        )
        return Template(
            id = id,
            title = title,
            description = desc,
            iconName = iconName,
            rule = prototypeRule,
            requiredTier = explicitTier ?: prototypeRule.requiredTier,
        )
    }
}

/**
 * Registry of all 20 built-in self-contained [TemplateProvider] definitions.
 */
object BuiltInTemplateCatalog {
    fun allProviders(): Set<TemplateProvider> =
        providersOneToSeven() + providersEightToFourteen() + providersFifteenToTwenty()

    private fun providersOneToSeven(): Set<TemplateProvider> = setOf(
        StaticTemplateProvider(
            order = 1,
            id = "tpl_01_wifi_off_bedtime",
            titleRes = R.string.tpl_01_title,
            descRes = R.string.tpl_01_desc,
            iconName = "wifi_off",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "23", "minute" to "0")),
            actions = listOf(Action(ActionType.WIFI_TOGGLE, mapOf("enabled" to "false"))),
        ),
        StaticTemplateProvider(
            order = 2,
            id = "tpl_02_dnd_meetings",
            titleRes = R.string.tpl_02_title,
            descRes = R.string.tpl_02_desc,
            iconName = "do_not_disturb",
            trigger = Trigger(TriggerType.CALENDAR_OR_MANUAL, mapOf("mode" to "CALENDAR_BUSY")),
            actions = listOf(Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "true"))),
        ),
        StaticTemplateProvider(
            order = 3,
            id = "tpl_03_auto_reply_driving",
            titleRes = R.string.tpl_03_title,
            descRes = R.string.tpl_03_desc,
            iconName = "directions_car",
            trigger = Trigger(TriggerType.BLUETOOTH_CONNECTED, mapOf("deviceCategory" to "CAR")),
            actions = listOf(
                Action(
                    ActionType.SMS_AUTO_REPLY,
                    mapOf("message" to "I am currently driving and will reply soon.", "mode" to "INTENT"),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 4,
            id = "tpl_04_data_limit_alert",
            titleRes = R.string.tpl_04_title,
            descRes = R.string.tpl_04_desc,
            iconName = "data_usage",
            trigger = Trigger(
                TriggerType.PERIODIC_CHECK,
                mapOf("metric" to "MOBILE_DATA_MB", "threshold" to "5000"),
            ),
            actions = listOf(
                Action(
                    ActionType.NOTIFY,
                    mapOf("title" to "Mobile Data Limit", "message" to "Monthly usage exceeded 5,000 MB."),
                ),
            ),
            cooldownSeconds = 3600L,
        ),
        StaticTemplateProvider(
            order = 5,
            id = "tpl_05_battery_saver_20",
            titleRes = R.string.tpl_05_title,
            descRes = R.string.tpl_05_desc,
            iconName = "battery_saver",
            trigger = Trigger(
                TriggerType.BATTERY_LEVEL,
                mapOf("thresholdPercent" to "20", "direction" to "BELOW"),
            ),
            actions = listOf(Action(ActionType.BATTERY_SAVER, mapOf("enabled" to "true"))),
            cooldownSeconds = 1800L,
        ),
        StaticTemplateProvider(
            order = 6,
            id = "tpl_06_bedtime_mode",
            titleRes = R.string.tpl_06_title,
            descRes = R.string.tpl_06_desc,
            iconName = "bedtime",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "22", "minute" to "30")),
            actions = listOf(
                Action(ActionType.DO_NOT_DISTURB, mapOf("enabled" to "true")),
                Action(ActionType.BRIGHTNESS, mapOf("percent" to "15")),
            ),
        ),
        StaticTemplateProvider(
            order = 7,
            id = "tpl_07_unplug_at_80",
            titleRes = R.string.tpl_07_title,
            descRes = R.string.tpl_07_desc,
            iconName = "battery_charging_full",
            trigger = Trigger(
                TriggerType.BATTERY_LEVEL,
                mapOf("thresholdPercent" to "80", "direction" to "ABOVE", "chargingOnly" to "true"),
            ),
            actions = listOf(
                Action(
                    ActionType.NOTIFY,
                    mapOf("title" to "Battery at 80%", "message" to "Unplug charger to extend battery health."),
                ),
            ),
            cooldownSeconds = 1800L,
        ),
    )

    private fun providersEightToFourteen(): Set<TemplateProvider> = setOf(
        StaticTemplateProvider(
            order = 8,
            id = "tpl_08_open_app_work_wifi",
            titleRes = R.string.tpl_08_title,
            descRes = R.string.tpl_08_desc,
            iconName = "work",
            trigger = Trigger(
                TriggerType.WIFI_SSID,
                mapOf("ssid" to "OfficeWiFi", "transition" to "CONNECTED"),
            ),
            actions = listOf(Action(ActionType.OPEN_APP, mapOf("category" to "NEWS"))),
        ),
        StaticTemplateProvider(
            order = 9,
            id = "tpl_09_silence_at_venue",
            titleRes = R.string.tpl_09_title,
            descRes = R.string.tpl_09_desc,
            iconName = "volume_off",
            trigger = Trigger(
                TriggerType.LOCATION_GEOFENCE,
                mapOf(
                    "latitude" to "24.8607",
                    "longitude" to "67.0011",
                    "radiusMeters" to "150",
                    "transition" to "ENTER",
                ),
            ),
            actions = listOf(Action(ActionType.RINGER_MODE, mapOf("mode" to "SILENT"))),
        ),
        StaticTemplateProvider(
            order = 10,
            id = "tpl_10_morning_briefing",
            titleRes = R.string.tpl_10_title,
            descRes = R.string.tpl_10_desc,
            iconName = "wb_sunny",
            trigger = Trigger(
                TriggerType.TIME,
                mapOf("hour" to "7", "minute" to "30", "requireCharging" to "true"),
            ),
            actions = listOf(
                Action(ActionType.OPEN_APP, mapOf("category" to "NEWS")),
                Action(
                    ActionType.NOTIFY,
                    mapOf(
                        "title" to "Morning Briefing",
                        "message" to "Good morning! Clear skies ahead — check your day's agenda.",
                        "includeLocalBriefing" to "true",
                    ),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 11,
            id = "tpl_11_lock_rotation_video",
            titleRes = R.string.tpl_11_title,
            descRes = R.string.tpl_11_desc,
            iconName = "screen_lock_rotation",
            trigger = Trigger(
                TriggerType.APP_OPENED,
                mapOf("packageNames" to "org.videolan.vlc,com.google.android.youtube"),
            ),
            actions = listOf(Action(ActionType.ROTATION_LOCK, mapOf("locked" to "true"))),
        ),
        StaticTemplateProvider(
            order = 12,
            id = "tpl_12_night_light_sunset",
            titleRes = R.string.tpl_12_title,
            descRes = R.string.tpl_12_desc,
            iconName = "nightlight",
            trigger = Trigger(TriggerType.TIME, mapOf("hour" to "19", "minute" to "0")),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SECURE", "key" to "night_display_activated", "value" to "1"),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 13,
            id = "tpl_13_auto_disable_hotspot",
            titleRes = R.string.tpl_13_title,
            descRes = R.string.tpl_13_desc,
            iconName = "wifi_tethering_off",
            trigger = Trigger(TriggerType.HOTSPOT_DURATION, mapOf("durationMinutes" to "30")),
            actions = listOf(Action(ActionType.HOTSPOT_TOGGLE, mapOf("enabled" to "false"))),
        ),
        StaticTemplateProvider(
            order = 14,
            id = "tpl_14_low_storage_alert",
            titleRes = R.string.tpl_14_title,
            descRes = R.string.tpl_14_desc,
            iconName = "sd_storage",
            trigger = Trigger(
                TriggerType.PERIODIC_CHECK,
                mapOf("metric" to "FREE_STORAGE_PERCENT", "threshold" to "10"),
            ),
            actions = listOf(
                Action(
                    ActionType.NOTIFY,
                    mapOf("title" to "Low Storage Space", "message" to "Free internal storage is below 10%."),
                ),
            ),
            cooldownSeconds = 3600L,
        ),
    )

    private fun providersFifteenToTwenty(): Set<TemplateProvider> = setOf(
        StaticTemplateProvider(
            order = 15,
            id = "tpl_15_music_on_headphones",
            titleRes = R.string.tpl_15_title,
            descRes = R.string.tpl_15_desc,
            iconName = "headphones",
            trigger = Trigger(TriggerType.BLUETOOTH_CONNECTED, mapOf("deviceCategory" to "AUDIO")),
            actions = listOf(Action(ActionType.OPEN_APP, mapOf("category" to "MUSIC"))),
        ),
        StaticTemplateProvider(
            order = 16,
            id = "tpl_16_wifi_off_leaving_home",
            titleRes = R.string.tpl_16_title,
            descRes = R.string.tpl_16_desc,
            iconName = "home",
            trigger = Trigger(
                TriggerType.WIFI_SSID,
                mapOf("ssid" to "HomeWiFi", "transition" to "DISCONNECTED"),
            ),
            actions = listOf(Action(ActionType.WIFI_TOGGLE, mapOf("enabled" to "false"))),
        ),
        StaticTemplateProvider(
            order = 17,
            id = "tpl_17_shorten_screen_timeout",
            titleRes = R.string.tpl_17_title,
            descRes = R.string.tpl_17_desc,
            iconName = "timer",
            trigger = Trigger(TriggerType.APP_OPENED, mapOf("packageNames" to "com.android.chrome")),
            actions = listOf(
                Action(
                    ActionType.SECURE_SETTING,
                    mapOf("namespace" to "SYSTEM", "key" to "screen_off_timeout", "value" to "15000"),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 18,
            id = "tpl_18_weekly_photo_backup",
            titleRes = R.string.tpl_18_title,
            descRes = R.string.tpl_18_desc,
            iconName = "photo_library",
            trigger = Trigger(
                TriggerType.TIME,
                mapOf("hour" to "18", "minute" to "0", "daysOfWeek" to "SUN"),
            ),
            actions = listOf(
                Action(
                    ActionType.NOTIFY,
                    mapOf("title" to "Photo Backup Reminder", "message" to "Back up this week's photos locally."),
                ),
            ),
        ),
        StaticTemplateProvider(
            order = 19,
            id = "tpl_19_silence_unknown_callers",
            titleRes = R.string.tpl_19_title,
            descRes = R.string.tpl_19_desc,
            iconName = "phone_disabled",
            trigger = Trigger(TriggerType.CALL_RECEIVED, mapOf("unknownOnly" to "true")),
            actions = listOf(Action(ActionType.SILENCE_UNKNOWN_CALLER, mapOf("reject" to "false"))),
        ),
        StaticTemplateProvider(
            order = 20,
            id = "tpl_20_weather_alarm_dismissed",
            titleRes = R.string.tpl_20_title,
            descRes = R.string.tpl_20_desc,
            iconName = "alarm_on",
            trigger = Trigger(TriggerType.ALARM_DISMISSED),
            actions = listOf(
                Action(
                    ActionType.NOTIFY,
                    mapOf(
                        "title" to "Good Morning Summary",
                        "message" to "Alarm dismissed — have a focused day!",
                        "includeLocalBriefing" to "true",
                    ),
                ),
            ),
        ),
    )
}

@Module
@InstallIn(SingletonComponent::class)
object BuiltInTemplatesModule {
    @Provides
    @ElementsIntoSet
    fun provideBuiltInTemplates(): Set<TemplateProvider> = BuiltInTemplateCatalog.allProviders()
}

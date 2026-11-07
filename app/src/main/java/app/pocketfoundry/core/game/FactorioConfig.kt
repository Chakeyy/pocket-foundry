package app.pocketfoundry.core.game

import android.content.Context
import app.pocketfoundry.core.system.Paths
import app.pocketfoundry.core.system.Settings
import app.pocketfoundry.core.drivers.Drivers.Compression
import java.io.File

/** Writes our settings into config.ini; the player's other settings stay. */
object FactorioConfig {
    private const val TUNING_VERSION = 9
    /** Without its version header Factorio calls the file invalid and offers a reset. */
    private const val VERSION_HEADER = "; version=13\n"
    /** The settings that halved what the CPU driver draws in a game; all on by default. */
    private val EFFECTS = listOf("show-smoke", "show-clouds", "show-fog", "show-space-dust", "show-decoratives",
                                 "show-particles", "show-item-shadows", "show-inserter-shadows", "additional-terrain-effects",
                                 "high-quality-terrain", "elevated-rail-shadows", "show-tree-distortion",
                                 "show-animated-water", "occlude-light-sprites")

    fun write(context: Context, paths: Paths, keyboard: Boolean, compression: Compression) {
        val settings = Settings(context)
        val light = settings.lightGraphics
        val uncompressed = compression == Compression.NONE
        val old = if (paths.config.exists()) paths.config.readText() else ""
        // Factorio derives its paths from its executable (here the loader), so name them.
        var config = update(old, File(paths.game, "data").path, paths.gameData.path, keyboard,
                            settings.spriteQuality, compression, textures(uncompressed, settings.uncompressedApplied),
                            effects(light, settings.lightApplied))
        val forceTuning = settings.tuningVersion < TUNING_VERSION
        config = applyTuning(config, forceTuning)
        paths.config.parentFile?.mkdirs()
        paths.config.writeText(config)
        settings.tuningVersion = TUNING_VERSION
        settings.lightApplied = light
        settings.uncompressedApplied = uncompressed
    }

    /** Engine defaults tuned for mobile SoC performance (Mali and DynamIQ SoCs). Preserves existing values unless migrating. */
    internal fun applyTuning(ini: String, overwrite: Boolean = false): String {
        var config = ini
        val graphicsDefaults = listOf(
            "lights-render-quality" to "0.25",
            "video-memory-usage" to "high",
            "tree-sprite-mipmaps" to "false",
            "trilinear-filtering" to "false",
            "low-quality-sprite-rotation" to "true",
            "atlas-specialization" to "false",
            "separate-lower-object-atlas" to "false",
            "cache-sprite-atlas" to "false",
            "compress-sprite-atlas-cache" to "false",
            "max-render-threads" to "1",
            "max-threads" to "2",
            "v-sync" to "true",
            "show-animated-water" to "false",
            "show-tree-distortion" to "false",
            "occlude-light-sprites" to "false",
            "max-texture-size" to "4096",
            "texture-streaming" to "false",
            "show-clouds" to "false",
            "show-fog" to "false",
            "show-space-dust" to "false",
            "show-smoke" to "false",
            "show-particles" to "false",
            "show-item-shadows" to "false",
            "show-inserter-shadows" to "false",
            "show-decoratives" to "false",
            "additional-terrain-effects" to "false",
            "high-quality-terrain" to "false"
        )
        for ((key, value) in graphicsDefaults) {
            if (overwrite || !Regex("(?m)^${Regex.escape(key)}=").containsMatchIn(config))
                config = setIni(config, "graphics", key, value)
        }
        val otherDefaults = listOf(
            "non-blocking-saving" to "true"
        )
        for ((key, value) in otherDefaults) {
            if (overwrite || !Regex("(?m)^${Regex.escape(key)}=").containsMatchIn(config))
                config = setIni(config, "other", key, value)
        }
        return config
    }

    /**
     * A driver that needs them gets uncompressed textures at every start. The next driver puts Factorio's default back
     * once; after that the game's menu decides (null).
     */
    internal fun textures(uncompressed: Boolean, uncompressedApplied: Boolean): String? = when {
        uncompressed -> "none"
        uncompressedApplied -> "low-quality"
        else -> null
    }

    /**
     * Light forces the effects off at every start. Turning Light off turns them on once; after that the game's menu
     * decides (null).
     */
    internal fun effects(light: Boolean, lightApplied: Boolean): Boolean? = if (light || lightApplied) !light else null

    internal fun update(ini: String, readData: String, writeData: String, keyboard: Boolean, spriteQuality: String,
                        compression: Compression, textures: String?, effects: Boolean?): String {
        var config = if (ini.startsWith("; version=")) ini else VERSION_HEADER
        config = setIni(config, "path", "read-data", readData)
        config = setIni(config, "path", "write-data", writeData)
        // Factorio doesn't switch between mouse and gamepad by itself.
        config = setIni(config, "input", "input-method", if (keyboard) "keyboard-and-mouse" else "game-controller")
        // Factorio's automatic UI scale is too small on a phone.
        if (!Regex("(?m)^ui-scale-mode=").containsMatchIn(config)) {
            config = setIni(config, "interface", "ui-scale-mode", "manual-display-points")
            config = setIni(config, "interface", "custom-proportional-ui-scale", "1.25")
        }
        config = setIni(config, "graphics", "graphics-quality", spriteQuality)
        for (key in listOf("gpu-accelerated-compression", "gpu-accelerated-mipmap-compression"))
            config = setIni(config, "graphics", key, (compression == Compression.GPU).toString())
        if (textures != null) config = setIni(config, "graphics", "texture-compression-level", textures)
        if (effects != null) for (key in EFFECTS) config = setIni(config, "graphics", key, effects.toString())
        return config
    }

    /** Sets [key] in [section], adding either where missing. */
    internal fun setIni(ini: String, section: String, key: String, value: String): String {
        val line = "$key=$value"
        val existing = Regex("(?m)^${Regex.escape(key)}=.*$")
        val header = Regex("(?m)^\\[${Regex.escape(section)}\\]$")
        val start = header.find(ini) ?: return ini.trimEnd('\n') + "\n\n[$section]\n$line\n"
        val end = Regex("(?m)^\\[").find(ini, start.range.last + 1)?.range?.first ?: ini.length
        val body = ini.substring(start.range.last + 1, end)
        val newBody = if (existing.containsMatchIn(body)) body.replace(existing, Regex.escapeReplacement(line))
                      else "\n$line" + body
        return ini.substring(0, start.range.last + 1) + newBody + ini.substring(end)
    }
}

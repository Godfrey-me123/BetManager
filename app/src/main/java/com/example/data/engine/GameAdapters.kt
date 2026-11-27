package com.example.data.engine

import com.example.data.model.ColorClassifier
import com.example.data.model.CrashColorCategory
import com.example.service.ScreenMap
import java.util.Locale

interface GameAdapter {
    val gameId: String
    val displayedGameName: String
    val supportedPlatforms: List<String>

    fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String> = emptyList(),
        rawTexts: List<String> = emptyList()
    ): Boolean

    fun detectMultiplierColor(
        pixelSampleHex: String?,
        multiplier: Double?,
        lowThreshold: Double = 2.00,
        bigThreshold: Double = 10.00
    ): Triple<String, CrashColorCategory, Boolean> {
        if (!pixelSampleHex.isNullOrBlank()) {
            val normalized = ColorClassifier.normalizeHex(pixelSampleHex)
            val category = ColorClassifier.classifyHex(normalized)
            return Triple(normalized, category, true)
        }
        val cat = if (multiplier != null && multiplier >= 1.0) {
            ColorClassifier.fallbackCategoryForMultiplier(multiplier, lowThreshold, bigThreshold)
        } else {
            CrashColorCategory.Other
        }
        return Triple(cat.defaultHex, cat, false)
    }
}

class AviatorGameAdapter : GameAdapter {
    override val gameId: String = "aviator"
    override val displayedGameName: String = "Aviator"
    override val supportedPlatforms: List<String> = listOf(
        "SportyBet",
        "BetPawa",
        "Chrome Web",
        "Mobile Browser",
        "Spribe Aviator"
    )

    override fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean {
        val lowerUrl = url?.lowercase(Locale.US).orEmpty()
        val lowerPkg = packageName.lowercase(Locale.US)
        val combined = (pageHints + rawTexts).joinToString(" ").lowercase(Locale.US)
        if (lowerUrl.contains("jetx") || lowerUrl.contains("spaceman")) return false
        return lowerUrl.contains("aviator") ||
            lowerUrl.contains("spribe") ||
            lowerUrl.contains("betpawa") ||
            lowerUrl.contains("sportybet") ||
            combined.contains("aviator") ||
            combined.contains("spribe") ||
            combined.contains("flew away") ||
            lowerPkg.contains("betpawa") ||
            lowerPkg.contains("sportybet")
    }
}

class JetXGameAdapter : GameAdapter {
    override val gameId: String = "jetx"
    override val displayedGameName: String = "JetX"
    override val supportedPlatforms: List<String> = listOf(
        "SmartSoft JetX",
        "BetPawa",
        "SportyBet",
        "Chrome Web"
    )

    override fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean {
        val lowerUrl = url?.lowercase(Locale.US).orEmpty()
        val combined = (pageHints + rawTexts).joinToString(" ").lowercase(Locale.US)
        return lowerUrl.contains("jetx") ||
            combined.contains("jetx") ||
            combined.contains("smartsoft") ||
            (combined.contains("collect") && combined.contains("jet"))
    }
}

class SpacemanGameAdapter : GameAdapter {
    override val gameId: String = "spaceman"
    override val displayedGameName: String = "Spaceman"
    override val supportedPlatforms: List<String> = listOf(
        "Pragmatic Spaceman",
        "SportyBet",
        "Chrome Web"
    )

    override fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean {
        val lowerUrl = url?.lowercase(Locale.US).orEmpty()
        val combined = (pageHints + rawTexts).joinToString(" ").lowercase(Locale.US)
        return lowerUrl.contains("spaceman") ||
            combined.contains("spaceman") ||
            combined.contains("pragmatic")
    }
}

class GenericCrashGameAdapter(
    dynamicGameId: String = "unknown",
    dynamicGameTitle: String = "Unknown"
) : GameAdapter {
    override val gameId: String = dynamicGameId
    override val displayedGameName: String = dynamicGameTitle
    override val supportedPlatforms: List<String> = listOf("Chrome Web", "Mobile Browser", "Native App")

    override fun detectGame(
        packageName: String,
        url: String?,
        pageHints: List<String>,
        rawTexts: List<String>
    ): Boolean {
        val pkgLower = packageName.lowercase(Locale.US)
        val combined = (pageHints + rawTexts).joinToString(" ").lowercase(Locale.US)
        return pkgLower.contains("chrome") ||
            pkgLower.contains("browser") ||
            combined.contains("crash") ||
            combined.contains("round history") ||
            combined.contains("flew away") ||
            combined.contains("multiplier")
    }
}

object GameAdapterRegistry {
    private val specificAdapters: List<GameAdapter> = listOf(
        JetXGameAdapter(),
        SpacemanGameAdapter(),
        AviatorGameAdapter()
    )

    fun allAdapters(): List<GameAdapter> = specificAdapters + GenericCrashGameAdapter()

    fun resolveForScreenMap(map: ScreenMap): GameAdapter {
        val hints = listOf(map.gameName, map.siteUrl)
        specificAdapters.firstOrNull {
            it.detectGame(map.packageName, map.siteUrl, hints, hints)
        }?.let { return it }

        val slugId = map.gameName.trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "unknown" }
        val title = map.gameName.ifBlank { "Aviator" }
        return GenericCrashGameAdapter(dynamicGameId = slugId, dynamicGameTitle = title)
    }
}

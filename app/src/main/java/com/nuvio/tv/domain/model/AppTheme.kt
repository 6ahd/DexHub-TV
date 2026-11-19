package com.nuvio.tv.domain.model

enum class AppTheme(val displayName: String) {
    DEX_HUB("Dex Hub"),   // Dex Hub TV (DEXHUB.md): lavender on navy, the theme a new install starts in
    CUSTOM("Custom"),
    GOLD("Gold"),
    JADE("Jade"),
    ROSE_GOLD("Rose Gold"),
    ARCTIC_BLUE("Arctic Blue"),
    GRAPHITE("Graphite"),
    CRIMSON("Crimson"),
    OCEAN("Ocean"),
    VIOLET("Violet"),
    EMERALD("Emerald"),
    AMBER("Amber"),
    COPPER("Copper"),
    ROSE("Rose"),
    WHITE("White"),
    GLASS("Glass")
}

/** Dex Hub TV (DEXHUB.md): the theme until one is chosen (Arc's was Glass). */
val DefaultAppTheme = AppTheme.DEX_HUB

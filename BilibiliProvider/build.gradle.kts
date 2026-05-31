// use an integer for version numbers
version = 9

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them
    description = "Bilibili TV - Anime, film, serial, dan konten Asia dari bilibili.tv"
    authors = listOf("BetbetMiro")
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // Beta only
    tvTypes = listOf(
        "Anime",
        "Movie",
        "TvSeries",
    )

    iconUrl = "https://play-lh.googleusercontent.com/G9s84Cm1TDnKDX2P8nipS_s60cuCnYtjBRRLespF8nivjXmbV9tF1fY37clZhXMLaA"

}

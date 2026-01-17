// use an integer for version numbers
version = 179

cloudstream {
    language = "id"
    // All of these properties are optional, you can safely remove them

     description = "Nonton awas jang sampe buta kawan"
     authors = listOf("AdiManuLateri3", "Trinity")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Anime",
        "Movie",
    )

    iconUrl = "https://raw.githubusercontent.com/michat88/Zaneta/refs/heads/main/Icons/adi.png"
}

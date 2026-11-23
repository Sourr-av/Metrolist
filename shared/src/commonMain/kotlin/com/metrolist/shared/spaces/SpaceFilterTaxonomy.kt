/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spaces

import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.settings.SpaceFilterSpec

data class SpaceLanguageOption(
    val id: String,
    val displayName: String,
    val searchKeywords: List<String>,
    val lastFmTags: Set<String>,
    val searchQueryTerm: String = displayName.lowercase(),
)

data class SpaceGenreOption(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val parentId: String? = null,
    val searchKeywords: List<String>,
    val lastFmTags: Set<String>,
    val excludedTags: Set<String> = emptySet(),
    val ytChipHints: List<String> = emptyList(),
    val searchQueryTerm: String = displayName.substringBefore(" (").lowercase(),
)

object SpaceFilterTaxonomy {
    val languages: List<SpaceLanguageOption> =
        listOf(
            SpaceLanguageOption(
                id = "english",
                displayName = "English",
                searchKeywords = listOf("english", "en", "eng", "uk", "us", "american", "british"),
                lastFmTags =
                    setOf(
                        "english", "english lyrics", "american", "british", "uk", "usa", "us",
                        "australian", "canadian", "irish", "scottish", "new zealand", "britpop",
                        "uk rap", "uk drill", "uk garage", "uk hip hop", "us rap", "grime",
                        "nashville", "americana", "southern rap", "east coast", "west coast",
                        "midwest emo", "british invasion",
                    ),
            ),
            SpaceLanguageOption(
                id = "spanish",
                displayName = "Spanish",
                searchKeywords = listOf("spanish", "espanol", "español", "latin", "latino", "es"),
                lastFmTags =
                    setOf(
                        "spanish", "espanol", "español", "latin", "latino", "latina", "reggaeton",
                        "latin pop", "latin trap", "latin rock", "rock en espanol", "rock en español",
                        "pop latino", "urbano latino", "musica mexicana", "regional mexican",
                        "corridos", "corridos tumbados", "bachata", "salsa", "cumbia", "merengue",
                        "flamenco", "mexican", "mexico", "colombian", "colombia", "argentine",
                        "argentinian", "argentina", "chilean", "puerto rican", "spain", "spanish pop",
                        "spanish rock", "spanish rap", "rap en espanol", "rap en español",
                    ),
            ),
            SpaceLanguageOption(
                id = "hindi",
                displayName = "Hindi",
                searchKeywords = listOf("hindi", "bollywood", "indian", "desi", "hi"),
                lastFmTags =
                    setOf(
                        "hindi", "bollywood", "indian", "desi", "indian pop", "hindi pop", "indipop",
                        "desi hip hop", "indian hip hop", "hindi rap", "filmi", "hindustani", "sufi",
                        "india", "bollywood soundtrack",
                    ),
            ),
            SpaceLanguageOption(
                id = "japanese",
                displayName = "Japanese",
                searchKeywords = listOf("japanese", "jp", "ja", "jpop", "j-pop", "jrock", "j-rock", "anime"),
                lastFmTags =
                    setOf(
                        "japanese", "japan", "j-pop", "jpop", "j-rock", "jrock", "j-rap", "jrap",
                        "japanese hip hop", "japanese rap", "japanese rock", "japanese pop",
                        "japanese indie", "anime", "anison", "vocaloid", "city pop", "shibuya-kei",
                        "visual kei", "enka", "nihongo", "japanese metal",
                    ),
            ),
            SpaceLanguageOption(
                id = "korean",
                displayName = "Korean",
                searchKeywords = listOf("korean", "kr", "ko", "kpop", "k-pop", "krap", "k-rap", "k-indie"),
                lastFmTags =
                    setOf(
                        "korean", "korea", "k-pop", "kpop", "k-rock", "krock", "k-rap", "krap",
                        "k-hip-hop", "khiphop", "korean hip hop", "korean rap", "k-indie", "kindie",
                        "k-r&b", "krnb", "korean r&b", "korean pop", "korean rock", "trot",
                        "kdrama", "k-drama",
                    ),
            ),
            SpaceLanguageOption(
                id = "malayalam",
                displayName = "Malayalam",
                searchKeywords = listOf("malayalam", "mollywood", "kerala", "ml"),
                lastFmTags =
                    setOf(
                        "malayalam", "mollywood", "kerala", "malayalam pop", "malayalam rap",
                        "malayalam hip hop", "malayalam songs",
                    ),
            ),
            SpaceLanguageOption(
                id = "tamil",
                displayName = "Tamil",
                searchKeywords = listOf("tamil", "kollywood", "ta"),
                lastFmTags =
                    setOf(
                        "tamil", "kollywood", "tamil pop", "tamil rap", "tamil hip hop",
                        "chennai", "tamil songs",
                    ),
            ),
            SpaceLanguageOption(
                id = "telugu",
                displayName = "Telugu",
                searchKeywords = listOf("telugu", "tollywood", "te"),
                lastFmTags =
                    setOf(
                        "telugu", "tollywood", "telugu pop", "telugu songs",
                    ),
            ),
            SpaceLanguageOption(
                id = "punjabi",
                displayName = "Punjabi",
                searchKeywords = listOf("punjabi", "bhangra", "pa"),
                lastFmTags =
                    setOf(
                        "punjabi", "bhangra", "punjabi pop", "punjabi rap", "punjabi hip hop",
                        "punjab",
                    ),
            ),
            SpaceLanguageOption(
                id = "french",
                displayName = "French",
                searchKeywords = listOf("french", "francais", "français", "fr"),
                lastFmTags =
                    setOf(
                        "french", "france", "francais", "français", "chanson", "chanson francaise",
                        "chanson française", "french pop", "french rap", "french hip hop",
                        "rap francais", "rap français", "french rock", "french indie",
                        "french house", "french touch", "quebec", "quebecois",
                    ),
            ),
            SpaceLanguageOption(
                id = "german",
                displayName = "German",
                searchKeywords = listOf("german", "deutsch", "de"),
                lastFmTags =
                    setOf(
                        "german", "germany", "deutsch", "deutschrap", "deutschrock", "german rap",
                        "german hip hop", "german pop", "german rock", "neue deutsche welle",
                        "schlager", "krautrock", "austrian",
                    ),
            ),
            SpaceLanguageOption(
                id = "portuguese",
                displayName = "Portuguese",
                searchKeywords = listOf("portuguese", "brazilian", "brasil", "brazil", "pt"),
                lastFmTags =
                    setOf(
                        "portuguese", "portugal", "brazilian", "brazil", "brasil", "mpb",
                        "bossa nova", "sertanejo", "funk carioca", "funk brasileiro", "pagode",
                        "samba", "forro", "forró", "fado", "rap nacional", "brazilian pop",
                        "brazilian rock", "brazilian hip hop", "trap br", "baile funk",
                    ),
            ),
            SpaceLanguageOption(
                id = "italian",
                displayName = "Italian",
                searchKeywords = listOf("italian", "italiano", "it"),
                lastFmTags =
                    setOf(
                        "italian", "italy", "italiano", "italian pop", "italian rock", "italian rap",
                        "italian hip hop", "italo disco", "cantautori",
                    ),
            ),
            SpaceLanguageOption(
                id = "russian",
                displayName = "Russian",
                searchKeywords = listOf("russian", "ru", "русский"),
                lastFmTags =
                    setOf(
                        "russian", "russia", "russian rock", "russian pop", "russian rap",
                        "russian hip hop", "russian post-punk", "soviet",
                    ),
            ),
            SpaceLanguageOption(
                id = "arabic",
                displayName = "Arabic",
                searchKeywords = listOf("arabic", "ar", "arab"),
                lastFmTags =
                    setOf(
                        "arabic", "arab", "arabic pop", "arabic rap", "arabic hip hop",
                        "egyptian", "lebanese", "moroccan", "khaleeji", "rai", "mahraganat",
                    ),
            ),
            SpaceLanguageOption(
                id = "chinese",
                displayName = "Chinese",
                searchKeywords = listOf("chinese", "mandarin", "cantonese", "cpop", "c-pop", "mandopop", "cantopop", "zh"),
                lastFmTags =
                    setOf(
                        "chinese", "china", "mandarin", "cantonese", "c-pop", "cpop",
                        "mandopop", "cantopop", "taiwanese", "taiwan", "hong kong",
                        "chinese pop", "chinese rock", "chinese hip hop", "chinese rap",
                    ),
            ),
            SpaceLanguageOption(
                id = "turkish",
                displayName = "Turkish",
                searchKeywords = listOf("turkish", "turkce", "türkçe", "tr"),
                lastFmTags =
                    setOf(
                        "turkish", "turkey", "turkce", "türkçe", "turkish pop", "turkish rock",
                        "turkish rap", "turkish hip hop", "anatolian rock", "arabesk",
                    ),
            ),
            SpaceLanguageOption(
                id = "indonesian",
                displayName = "Indonesian",
                searchKeywords = listOf("indonesian", "indo", "bahasa", "id"),
                lastFmTags =
                    setOf(
                        "indonesian", "indonesia", "indonesian pop", "indonesian rock",
                        "indonesian indie", "dangdut", "pop indonesia",
                    ),
            ),
            SpaceLanguageOption(
                id = "thai",
                displayName = "Thai",
                searchKeywords = listOf("thai", "thailand", "t-pop", "th"),
                lastFmTags = setOf("thai", "thailand", "thai pop", "t-pop", "thai rock", "thai hip hop"),
            ),
            SpaceLanguageOption(
                id = "vietnamese",
                displayName = "Vietnamese",
                searchKeywords = listOf("vietnamese", "v-pop", "vpop", "vi"),
                lastFmTags = setOf("vietnamese", "vietnam", "v-pop", "vpop", "vietnamese pop", "vietnamese rap"),
            ),
            SpaceLanguageOption(
                id = "polish",
                displayName = "Polish",
                searchKeywords = listOf("polish", "polski", "pl"),
                lastFmTags = setOf("polish", "poland", "polski", "polish rock", "polish rap", "polish hip hop", "polish pop"),
            ),
            SpaceLanguageOption(
                id = "swedish",
                displayName = "Swedish",
                searchKeywords = listOf("swedish", "svenska", "sv"),
                lastFmTags = setOf("swedish", "sweden", "svenska", "swedish pop", "swedish rock", "swedish hip hop"),
            ),
            SpaceLanguageOption(
                id = "dutch",
                displayName = "Dutch",
                searchKeywords = listOf("dutch", "nederlands", "nl"),
                lastFmTags = setOf("dutch", "netherlands", "nederlands", "dutch pop", "dutch rap", "dutch hip hop", "nederpop"),
            ),
            SpaceLanguageOption(
                id = "instrumental",
                displayName = "Instrumental (No Vocals)",
                searchKeywords = listOf("instrumental", "no vocals", "score", "orchestral"),
                lastFmTags =
                    setOf(
                        "instrumental", "instrumental hip hop", "instrumental rock",
                        "instrumental metal", "no vocals",
                    ),
                searchQueryTerm = "instrumental",
            ),
        )

    val genres: List<SpaceGenreOption> =
        listOf(
            // --- Soundtracks hierarchy ---
            SpaceGenreOption(
                id = "soundtracks",
                displayName = "Soundtracks (All)",
                description = "Video game, movie & TV soundtracks combined",
                searchKeywords = listOf("soundtracks", "soundtrack", "ost", "score", "scores", "vgm", "film", "movie", "game"),
                lastFmTags =
                    setOf(
                        "soundtrack", "soundtracks", "ost", "original soundtrack", "score",
                        "original score", "anime soundtrack", "anime ost", "tv soundtrack",
                        "television soundtrack", "musical", "broadway", "orchestral soundtrack",
                    ),
                ytChipHints = listOf("Focus", "Relax", "Commute", "Mood"),
                searchQueryTerm = "soundtrack ost",
            ),
            SpaceGenreOption(
                id = "soundtracks_video_game",
                displayName = "Video Game Soundtracks",
                description = "Game OSTs, VGM & chiptune",
                parentId = "soundtracks",
                searchKeywords = listOf("video game soundtracks", "video game", "game soundtrack", "soundtracks", "vgm", "game ost", "chiptune"),
                lastFmTags =
                    setOf(
                        "video game music", "video game soundtrack", "video game", "video games",
                        "vgm", "game soundtrack", "game music", "game ost", "videogame",
                        "videogame music", "nintendo", "playstation", "chiptune", "8-bit",
                        "16-bit", "rpg soundtrack", "jrpg", "gaming", "game score",
                    ),
                ytChipHints = listOf("Focus", "Energize", "Gaming"),
                searchQueryTerm = "video game soundtrack ost",
            ),
            SpaceGenreOption(
                id = "soundtracks_movie",
                displayName = "Movie Soundtracks",
                description = "Film scores & motion picture OSTs",
                parentId = "soundtracks",
                searchKeywords = listOf("movie soundtracks", "movie", "film", "film score", "soundtracks", "cinema", "motion picture"),
                lastFmTags =
                    setOf(
                        "movie soundtrack", "film soundtrack", "film score", "film music",
                        "cinema", "cinematic", "motion picture", "movie score", "hollywood",
                        "disney", "bollywood soundtrack", "filmi", "movie", "films", "score",
                    ),
                excludedTags = setOf("video game music", "video game soundtrack", "video game", "vgm", "game soundtrack", "game ost", "chiptune"),
                ytChipHints = listOf("Focus", "Relax", "Romance", "Sad"),
                searchQueryTerm = "movie soundtrack film score",
            ),
            // --- Hip-Hop hierarchy ---
            SpaceGenreOption(
                id = "hip_hop",
                displayName = "Hip-Hop / Rap (All)",
                description = "Hip-hop, rap, trap, drill & boom bap",
                searchKeywords = listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "drill", "boom bap"),
                lastFmTags =
                    setOf(
                        "hip hop", "hip-hop", "hiphop", "rap", "conscious hip hop", "gangsta rap",
                        "underground hip hop", "east coast rap", "west coast rap", "southern rap",
                        "uk rap", "uk hip hop", "desi hip hop", "jazz rap", "cloud rap", "emo rap",
                        "alternative hip hop", "experimental hip hop", "grime", "drill", "uk drill",
                        "hardcore hip hop", "melodic rap", "rap rock", "rap metal",
                    ),
                ytChipHints = listOf("Hip-hop", "Rap", "Energize", "Workout", "Party"),
                searchQueryTerm = "hip hop rap",
            ),
            SpaceGenreOption(
                id = "trap",
                displayName = "Trap",
                parentId = "hip_hop",
                searchKeywords = listOf("trap", "hip hop", "rap", "latin trap"),
                lastFmTags = setOf("trap", "trap music", "latin trap", "trap metal", "southern trap", "rage"),
                ytChipHints = listOf("Hip-hop", "Energize", "Workout", "Party"),
                searchQueryTerm = "trap",
            ),
            SpaceGenreOption(
                id = "old_school_hip_hop",
                displayName = "Old School Hip-Hop",
                parentId = "hip_hop",
                searchKeywords = listOf("old school hip hop", "boom bap", "90s hip hop", "classic rap", "hip hop"),
                lastFmTags = setOf("old school hip hop", "boom bap", "90s hip hop", "90s rap", "golden age hip hop", "classic hip hop"),
                ytChipHints = listOf("Hip-hop", "Commute"),
                searchQueryTerm = "old school hip hop",
            ),
            SpaceGenreOption(
                id = "lo_fi_hip_hop",
                displayName = "Lo-Fi Hip-Hop",
                parentId = "hip_hop",
                searchKeywords = listOf("lo-fi hip hop", "lofi", "lo-fi", "chillhop", "beats", "hip hop"),
                lastFmTags = setOf("lo-fi hip hop", "lofi hip hop", "chillhop", "lo-fi", "lofi", "instrumental hip hop", "jazzy hip hop", "study beats"),
                ytChipHints = listOf("Focus", "Relax", "Chill", "Sleep"),
                searchQueryTerm = "lo-fi hip hop",
            ),
            // --- Pop hierarchy ---
            SpaceGenreOption(
                id = "pop",
                displayName = "Pop (All)",
                description = "Mainstream, indie, synth & dance pop",
                searchKeywords = listOf("pop", "dance pop", "synth pop", "indie pop", "electropop"),
                lastFmTags =
                    setOf(
                        "pop", "dance pop", "electropop", "electro pop", "pop rock", "teen pop",
                        "art pop", "dream pop", "bedroom pop", "chamber pop", "power pop",
                        "hyperpop", "britpop", "city pop", "latin pop", "bubblegum pop",
                        "baroque pop", "europop", "folk pop", "country pop", "pop rap", "pop punk",
                    ),
                ytChipHints = listOf("Pop", "Feel good", "Party", "Romance"),
                searchQueryTerm = "pop",
            ),
            SpaceGenreOption(
                id = "indie_pop",
                displayName = "Indie Pop",
                parentId = "pop",
                searchKeywords = listOf("indie pop", "bedroom pop", "dream pop", "pop", "indie"),
                lastFmTags = setOf("indie pop", "bedroom pop", "dream pop", "chamber pop", "twee pop", "jangle pop"),
                ytChipHints = listOf("Indie", "Feel good", "Relax"),
                searchQueryTerm = "indie pop",
            ),
            SpaceGenreOption(
                id = "synth_pop",
                displayName = "Synth-Pop",
                parentId = "pop",
                searchKeywords = listOf("synth pop", "synth-pop", "synthpop", "electropop", "pop"),
                lastFmTags = setOf("synthpop", "synth-pop", "synth pop", "electropop", "electro pop", "new wave", "darkwave"),
                ytChipHints = listOf("Pop", "Party", "Energize"),
                searchQueryTerm = "synth pop",
            ),
            SpaceGenreOption(
                id = "k_pop",
                displayName = "K-Pop",
                parentId = "pop",
                searchKeywords = listOf("k-pop", "kpop", "korean pop", "pop"),
                lastFmTags = setOf("k-pop", "kpop", "korean pop"),
                ytChipHints = listOf("Pop", "Party", "Feel good"),
                searchQueryTerm = "k-pop",
            ),
            SpaceGenreOption(
                id = "j_pop",
                displayName = "J-Pop",
                parentId = "pop",
                searchKeywords = listOf("j-pop", "jpop", "japanese pop", "city pop", "pop"),
                lastFmTags = setOf("j-pop", "jpop", "japanese pop", "city pop", "anison"),
                ytChipHints = listOf("Pop", "Feel good"),
                searchQueryTerm = "j-pop",
            ),
            // --- Rock hierarchy ---
            SpaceGenreOption(
                id = "rock",
                displayName = "Rock (All)",
                description = "Alternative, indie, classic, hard & punk rock",
                searchKeywords = listOf("rock", "alt rock", "indie rock", "classic rock", "punk", "hard rock"),
                lastFmTags =
                    setOf(
                        "rock", "pop rock", "folk rock", "prog rock", "progressive rock",
                        "psychedelic rock", "garage rock", "post-rock", "post rock", "blues rock",
                        "soft rock", "grunge", "shoegaze", "emo", "math rock", "stoner rock",
                        "art rock", "glam rock", "southern rock", "surf rock", "rap rock",
                        "country rock", "j-rock", "jrock", "rock en espanol", "rock en español",
                    ),
                ytChipHints = listOf("Rock", "Energize", "Workout", "Commute"),
                searchQueryTerm = "rock",
            ),
            SpaceGenreOption(
                id = "alternative_rock",
                displayName = "Alternative Rock",
                parentId = "rock",
                searchKeywords = listOf("alternative rock", "alt rock", "alternative", "grunge", "rock"),
                lastFmTags = setOf("alternative rock", "alt rock", "alternative", "grunge", "post-grunge", "britpop"),
                ytChipHints = listOf("Rock", "Commute"),
                searchQueryTerm = "alternative rock",
            ),
            SpaceGenreOption(
                id = "indie_rock",
                displayName = "Indie Rock",
                parentId = "rock",
                searchKeywords = listOf("indie rock", "indie", "rock", "garage rock"),
                lastFmTags = setOf("indie rock", "garage rock", "post-punk revival", "lo-fi rock", "math rock"),
                ytChipHints = listOf("Indie", "Rock", "Commute"),
                searchQueryTerm = "indie rock",
            ),
            SpaceGenreOption(
                id = "classic_rock",
                displayName = "Classic Rock",
                parentId = "rock",
                searchKeywords = listOf("classic rock", "70s rock", "80s rock", "rock"),
                lastFmTags = setOf("classic rock", "70s rock", "80s rock", "arena rock", "blues rock", "heartland rock"),
                ytChipHints = listOf("Rock", "Commute", "Feel good"),
                searchQueryTerm = "classic rock",
            ),
            SpaceGenreOption(
                id = "punk_rock",
                displayName = "Punk Rock",
                parentId = "rock",
                searchKeywords = listOf("punk", "punk rock", "pop punk", "post-punk", "rock"),
                lastFmTags = setOf("punk", "punk rock", "pop punk", "post-punk", "hardcore punk", "skate punk", "emo"),
                ytChipHints = listOf("Rock", "Energize", "Workout"),
                searchQueryTerm = "punk rock",
            ),
            SpaceGenreOption(
                id = "hard_rock",
                displayName = "Hard Rock",
                parentId = "rock",
                searchKeywords = listOf("hard rock", "rock"),
                lastFmTags = setOf("hard rock", "stoner rock", "glam metal", "heavy rock"),
                ytChipHints = listOf("Rock", "Energize", "Workout"),
                searchQueryTerm = "hard rock",
            ),
            // --- Electronic hierarchy ---
            SpaceGenreOption(
                id = "electronic",
                displayName = "Electronic (All)",
                description = "EDM, house, techno, DnB, dubstep & ambient",
                searchKeywords = listOf("electronic", "edm", "electronica", "dance", "house", "techno", "dnb"),
                lastFmTags =
                    setOf(
                        "electronic", "electronica", "electro", "dance", "club", "idm",
                        "trance", "future bass", "downtempo", "trip-hop", "trip hop",
                        "chillwave", "vaporwave", "breakbeat", "uk garage", "big beat",
                        "electropop", "electro pop",
                    ),
                ytChipHints = listOf("Party", "Energize", "Workout", "Focus"),
                searchQueryTerm = "electronic",
            ),
            SpaceGenreOption(
                id = "edm",
                displayName = "EDM / Dance",
                parentId = "electronic",
                searchKeywords = listOf("edm", "dance", "electronic", "club", "festival"),
                lastFmTags = setOf("edm", "dance", "progressive house", "electro house", "big room", "future bass", "tropical house"),
                ytChipHints = listOf("Party", "Workout", "Energize"),
                searchQueryTerm = "edm dance",
            ),
            SpaceGenreOption(
                id = "house",
                displayName = "House",
                parentId = "electronic",
                searchKeywords = listOf("house", "deep house", "tech house", "electronic"),
                lastFmTags = setOf("house", "deep house", "tech house", "french house", "progressive house", "electro house", "afro house"),
                ytChipHints = listOf("Party", "Energize", "Workout"),
                searchQueryTerm = "house music",
            ),
            SpaceGenreOption(
                id = "techno",
                displayName = "Techno",
                parentId = "electronic",
                searchKeywords = listOf("techno", "minimal techno", "electronic"),
                lastFmTags = setOf("techno", "minimal techno", "acid techno", "detroit techno", "melodic techno", "industrial techno"),
                ytChipHints = listOf("Party", "Workout", "Energize"),
                searchQueryTerm = "techno",
            ),
            SpaceGenreOption(
                id = "drum_and_bass",
                displayName = "Drum & Bass",
                parentId = "electronic",
                searchKeywords = listOf("drum and bass", "drum & bass", "dnb", "jungle", "electronic"),
                lastFmTags = setOf("drum and bass", "drum & bass", "dnb", "liquid drum and bass", "jungle", "neurofunk"),
                ytChipHints = listOf("Energize", "Workout", "Party"),
                searchQueryTerm = "drum and bass",
            ),
            SpaceGenreOption(
                id = "dubstep",
                displayName = "Dubstep",
                parentId = "electronic",
                searchKeywords = listOf("dubstep", "bass", "riddim", "electronic"),
                lastFmTags = setOf("dubstep", "brostep", "melodic dubstep", "riddim", "bass music"),
                ytChipHints = listOf("Energize", "Workout", "Party"),
                searchQueryTerm = "dubstep",
            ),
            SpaceGenreOption(
                id = "ambient",
                displayName = "Ambient",
                parentId = "electronic",
                searchKeywords = listOf("ambient", "chillout", "atmospheric", "electronic"),
                lastFmTags = setOf("ambient", "dark ambient", "drone", "chillout", "new age", "downtempo", "atmospheric"),
                ytChipHints = listOf("Sleep", "Focus", "Relax"),
                searchQueryTerm = "ambient",
            ),
            SpaceGenreOption(
                id = "synthwave",
                displayName = "Synthwave",
                parentId = "electronic",
                searchKeywords = listOf("synthwave", "retrowave", "outrun", "cyberpunk", "electronic"),
                lastFmTags = setOf("synthwave", "retrowave", "outrun", "darksynth", "cyberpunk", "chillwave", "vaporwave"),
                ytChipHints = listOf("Focus", "Energize", "Commute"),
                searchQueryTerm = "synthwave",
            ),
            // --- R&B & Soul ---
            SpaceGenreOption(
                id = "rnb",
                displayName = "R&B / Soul (All)",
                description = "R&B, soul, neo-soul & funk",
                searchKeywords = listOf("r&b", "rnb", "soul", "neo soul", "funk", "motown"),
                lastFmTags =
                    setOf(
                        "r&b", "rnb", "rhythm and blues", "soul", "contemporary r&b",
                        "alternative r&b", "motown", "quiet storm", "blue-eyed soul", "k-r&b", "krnb",
                    ),
                ytChipHints = listOf("Romance", "Relax", "Feel good", "Sad"),
                searchQueryTerm = "r&b soul",
            ),
            SpaceGenreOption(
                id = "neo_soul",
                displayName = "Neo-Soul",
                parentId = "rnb",
                searchKeywords = listOf("neo soul", "neo-soul", "soul", "r&b"),
                lastFmTags = setOf("neo-soul", "neo soul", "nu soul"),
                ytChipHints = listOf("Relax", "Romance"),
                searchQueryTerm = "neo soul",
            ),
            SpaceGenreOption(
                id = "funk",
                displayName = "Funk",
                parentId = "rnb",
                searchKeywords = listOf("funk", "disco", "groove", "r&b"),
                lastFmTags = setOf("funk", "p-funk", "funk rock", "disco", "nu-disco", "boogie"),
                ytChipHints = listOf("Feel good", "Party", "Energize"),
                searchQueryTerm = "funk",
            ),
            // --- Metal ---
            SpaceGenreOption(
                id = "metal",
                displayName = "Metal (All)",
                description = "Heavy, thrash, death, black & metalcore",
                searchKeywords = listOf("metal", "heavy metal", "metalcore", "thrash", "death metal", "black metal"),
                lastFmTags =
                    setOf(
                        "metal", "nu metal", "progressive metal", "power metal", "doom metal",
                        "symphonic metal", "death metal", "black metal", "alternative metal",
                        "industrial metal", "folk metal", "gothic metal", "sludge metal", "rap metal",
                        "trap metal",
                    ),
                ytChipHints = listOf("Energize", "Workout", "Rock"),
                searchQueryTerm = "metal",
            ),
            SpaceGenreOption(
                id = "heavy_metal",
                displayName = "Heavy Metal",
                parentId = "metal",
                searchKeywords = listOf("heavy metal", "metal", "nwobhm"),
                lastFmTags = setOf("heavy metal", "traditional heavy metal", "nwobhm"),
                ytChipHints = listOf("Workout", "Energize"),
                searchQueryTerm = "heavy metal",
            ),
            SpaceGenreOption(
                id = "metalcore",
                displayName = "Metalcore",
                parentId = "metal",
                searchKeywords = listOf("metalcore", "deathcore", "post-hardcore", "metal"),
                lastFmTags = setOf("metalcore", "deathcore", "melodic metalcore", "post-hardcore"),
                ytChipHints = listOf("Workout", "Energize"),
                searchQueryTerm = "metalcore",
            ),
            SpaceGenreOption(
                id = "thrash_metal",
                displayName = "Thrash Metal",
                parentId = "metal",
                searchKeywords = listOf("thrash metal", "thrash", "speed metal", "metal"),
                lastFmTags = setOf("thrash metal", "speed metal", "crossover thrash"),
                ytChipHints = listOf("Workout", "Energize"),
                searchQueryTerm = "thrash metal",
            ),
            // --- Other standalone major genres ---
            SpaceGenreOption(
                id = "jazz",
                displayName = "Jazz",
                searchKeywords = listOf("jazz", "smooth jazz", "bebop", "swing", "bossa nova"),
                lastFmTags =
                    setOf(
                        "jazz", "smooth jazz", "acid jazz", "nu jazz", "bebop", "hard bop",
                        "cool jazz", "jazz fusion", "swing", "big band", "vocal jazz", "jazz rap",
                        "bossa nova", "free jazz", "modal jazz",
                    ),
                ytChipHints = listOf("Relax", "Focus", "Romance"),
                searchQueryTerm = "jazz",
            ),
            SpaceGenreOption(
                id = "blues",
                displayName = "Blues",
                searchKeywords = listOf("blues", "delta blues", "blues rock"),
                lastFmTags = setOf("blues", "blues rock", "electric blues", "delta blues", "chicago blues", "soul blues"),
                ytChipHints = listOf("Relax", "Commute"),
                searchQueryTerm = "blues",
            ),
            SpaceGenreOption(
                id = "classical",
                displayName = "Classical",
                searchKeywords = listOf("classical", "orchestra", "symphony", "piano", "baroque", "opera"),
                lastFmTags =
                    setOf(
                        "classical", "modern classical", "contemporary classical", "neoclassical",
                        "baroque", "romantic", "orchestral", "symphony", "chamber music", "opera",
                        "piano", "violin", "cello",
                    ),
                ytChipHints = listOf("Focus", "Sleep", "Relax"),
                searchQueryTerm = "classical music",
            ),
            SpaceGenreOption(
                id = "country",
                displayName = "Country",
                searchKeywords = listOf("country", "americana", "bluegrass", "nashville"),
                lastFmTags =
                    setOf(
                        "country", "country pop", "country rock", "alt-country", "outlaw country",
                        "bluegrass", "americana", "modern country", "classic country", "bro-country",
                    ),
                ytChipHints = listOf("Commute", "Feel good", "Romance"),
                searchQueryTerm = "country",
            ),
            SpaceGenreOption(
                id = "folk",
                displayName = "Folk / Acoustic",
                searchKeywords = listOf("folk", "acoustic", "singer-songwriter", "indie folk"),
                lastFmTags =
                    setOf(
                        "folk", "indie folk", "folk rock", "folk pop", "acoustic", "singer-songwriter",
                        "contemporary folk", "traditional folk", "celtic", "americana",
                    ),
                ytChipHints = listOf("Relax", "Sad", "Commute", "Focus"),
                searchQueryTerm = "folk acoustic",
            ),
            SpaceGenreOption(
                id = "indie",
                displayName = "Indie",
                searchKeywords = listOf("indie", "independent"),
                lastFmTags = setOf("indie", "indie rock", "indie pop", "indie folk", "indietronica", "k-indie", "kindie"),
                ytChipHints = listOf("Commute", "Relax", "Feel good"),
                searchQueryTerm = "indie",
            ),
            SpaceGenreOption(
                id = "lofi_chill",
                displayName = "Lo-Fi / Chill",
                searchKeywords = listOf("lo-fi", "lofi", "chill", "chillout", "study"),
                lastFmTags = setOf("lo-fi", "lofi", "chill", "chillout", "chillwave", "chillhop", "downtempo", "lo-fi hip hop", "lofi hip hop"),
                ytChipHints = listOf("Relax", "Focus", "Sleep", "Chill"),
                searchQueryTerm = "lo-fi chill",
            ),
            SpaceGenreOption(
                id = "reggaeton",
                displayName = "Reggaeton / Latin Urban",
                searchKeywords = listOf("reggaeton", "latin urban", "urbano", "dembow"),
                lastFmTags = setOf("reggaeton", "urbano latino", "dembow", "latin trap", "perreo"),
                ytChipHints = listOf("Party", "Workout", "Energize"),
                searchQueryTerm = "reggaeton",
            ),
            SpaceGenreOption(
                id = "reggae",
                displayName = "Reggae / Dancehall",
                searchKeywords = listOf("reggae", "dancehall", "ska", "dub"),
                lastFmTags = setOf("reggae", "roots reggae", "dancehall", "dub", "ska", "rocksteady", "reggaeton"),
                ytChipHints = listOf("Relax", "Feel good", "Party"),
                searchQueryTerm = "reggae",
            ),
            SpaceGenreOption(
                id = "afrobeats",
                displayName = "Afrobeats",
                searchKeywords = listOf("afrobeats", "afrobeat", "amapiano", "afropop"),
                lastFmTags = setOf("afrobeats", "afrobeat", "amapiano", "afropop", "afro-fusion", "alte"),
                ytChipHints = listOf("Party", "Feel good", "Energize"),
                searchQueryTerm = "afrobeats",
            ),
            SpaceGenreOption(
                id = "phonk",
                displayName = "Phonk",
                searchKeywords = listOf("phonk", "drift phonk", "brazilian phonk"),
                lastFmTags = setOf("phonk", "drift phonk", "brazilian phonk", "memphis rap", "cowbell"),
                ytChipHints = listOf("Workout", "Energize"),
                searchQueryTerm = "phonk",
            ),
            SpaceGenreOption(
                id = "gospel",
                displayName = "Gospel / Christian",
                searchKeywords = listOf("gospel", "christian", "worship", "ccm"),
                lastFmTags = setOf("gospel", "christian", "worship", "christian rock", "ccm", "praise and worship"),
                ytChipHints = listOf("Feel good", "Relax"),
                searchQueryTerm = "gospel worship",
            ),
            SpaceGenreOption(
                id = "devotional",
                displayName = "Devotional / Spiritual",
                searchKeywords = listOf("devotional", "spiritual", "bhajan", "qawwali", "sufi"),
                lastFmTags = setOf("devotional", "spiritual", "bhajan", "qawwali", "sufi", "mantra", "kirtan"),
                ytChipHints = listOf("Relax", "Focus"),
                searchQueryTerm = "devotional",
            ),
        )

    private val languagesById: Map<String, SpaceLanguageOption> = languages.associateBy { it.id }
    private val genresById: Map<String, SpaceGenreOption> = genres.associateBy { it.id }
    private val childGenresByParent: Map<String, List<SpaceGenreOption>> = genres.filter { it.parentId != null }.groupBy { it.parentId!! }

    /**
     * Common Last.fm non-genre noise tags that should be ignored when checking genre conflicts.
     */
    private val noiseTags: Set<String> =
        setOf(
            "seen live", "favorites", "favourites", "favorite", "favourite", "my music",
            "awesome", "good", "love", "loved", "amazing", "beautiful", "cool", "best",
            "albums i own", "spotify", "youtube", "check out", "under 2000 listeners",
            "male vocalists", "female vocalists", "male vocalist", "female vocalist",
            "singer", "vocal", "vocals", "music", "songs", "song", "track", "tracks",
            "single", "album", "explicit", "clean", "remix", "cover", "live", "acoustic version",
            "50s", "60s", "70s", "80s", "90s", "00s", "10s", "20s",
            "1960s", "1970s", "1980s", "1990s", "2000s", "2010s", "2020s",
            "2020", "2021", "2022", "2023", "2024", "2025", "2026",
            "catchy", "chill", "sad", "happy", "party", "workout", "gym", "driving",
            "study", "sleep", "relax", "romantic", "epic", "dark", "melancholic",
            "classic", "oldies", "hits", "top 40", "billboard", "radio", "mainstream",
        )

    /**
     * Compound token expansions so tags like "pop rock", "electropop", "rap metal"
     * classify into all of their constituent genres.
     */
    private val compoundTokenToGenreIds: Map<String, Set<String>> =
        mapOf(
            "pop" to setOf("pop"),
            "rock" to setOf("rock"),
            "rap" to setOf("hip_hop"),
            "hiphop" to setOf("hip_hop"),
            "trap" to setOf("trap", "hip_hop"),
            "drill" to setOf("hip_hop"),
            "metal" to setOf("metal"),
            "metalcore" to setOf("metalcore", "metal"),
            "punk" to setOf("punk_rock", "rock"),
            "jazz" to setOf("jazz"),
            "blues" to setOf("blues"),
            "folk" to setOf("folk"),
            "country" to setOf("country"),
            "soul" to setOf("rnb"),
            "rnb" to setOf("rnb"),
            "r&b" to setOf("rnb"),
            "funk" to setOf("funk", "rnb"),
            "electronic" to setOf("electronic"),
            "electro" to setOf("electronic"),
            "edm" to setOf("edm", "electronic"),
            "house" to setOf("house", "electronic"),
            "techno" to setOf("techno", "electronic"),
            "dubstep" to setOf("dubstep", "electronic"),
            "ambient" to setOf("ambient", "electronic"),
            "synth" to setOf("electronic"),
            "synthwave" to setOf("synthwave", "electronic"),
            "synthpop" to setOf("synth_pop", "pop", "electronic"),
            "electropop" to setOf("pop", "electronic"),
            "classical" to setOf("classical"),
            "orchestral" to setOf("classical"),
            "reggae" to setOf("reggae"),
            "reggaeton" to setOf("reggaeton"),
            "afrobeats" to setOf("afrobeats"),
            "afrobeat" to setOf("afrobeats"),
            "phonk" to setOf("phonk"),
            "indie" to setOf("indie"),
            "lofi" to setOf("lofi_chill"),
            "soundtrack" to setOf("soundtracks"),
            "ost" to setOf("soundtracks"),
            "vgm" to setOf("soundtracks_video_game", "soundtracks"),
            "gospel" to setOf("gospel"),
            "christian" to setOf("gospel"),
            "devotional" to setOf("devotional"),
        )

    private val multiWordCompoundPhrases: Map<String, Set<String>> =
        mapOf(
            "hip hop" to setOf("hip_hop"),
            "pop rock" to setOf("pop", "rock"),
            "rap rock" to setOf("hip_hop", "rock"),
            "rap metal" to setOf("hip_hop", "metal"),
            "trap metal" to setOf("trap", "hip_hop", "metal"),
            "folk rock" to setOf("folk", "rock"),
            "folk pop" to setOf("folk", "pop"),
            "country pop" to setOf("country", "pop"),
            "country rock" to setOf("country", "rock"),
            "blues rock" to setOf("blues", "rock"),
            "jazz rap" to setOf("jazz", "hip_hop"),
            "pop punk" to setOf("pop", "punk_rock", "rock"),
            "synth pop" to setOf("synth_pop", "pop", "electronic"),
            "electro pop" to setOf("pop", "electronic"),
            "indie pop" to setOf("indie_pop", "pop", "indie"),
            "indie rock" to setOf("indie_rock", "rock", "indie"),
            "indie folk" to setOf("indie", "folk"),
            "pop rap" to setOf("pop", "hip_hop"),
            "funk rock" to setOf("funk", "rnb", "rock"),
            "drum and bass" to setOf("drum_and_bass", "electronic"),
            "drum & bass" to setOf("drum_and_bass", "electronic"),
            "video game" to setOf("soundtracks_video_game", "soundtracks"),
            "game soundtrack" to setOf("soundtracks_video_game", "soundtracks"),
            "game ost" to setOf("soundtracks_video_game", "soundtracks"),
            "movie soundtrack" to setOf("soundtracks_movie", "soundtracks"),
            "film soundtrack" to setOf("soundtracks_movie", "soundtracks"),
            "film score" to setOf("soundtracks_movie", "soundtracks"),
            "motion picture" to setOf("soundtracks_movie", "soundtracks"),
            "lo fi" to setOf("lofi_chill"),
            "lo fi hip hop" to setOf("lo_fi_hip_hop", "hip_hop", "lofi_chill"),
            "neo soul" to setOf("neo_soul", "rnb"),
            "old school hip hop" to setOf("old_school_hip_hop", "hip_hop"),
            "hard rock" to setOf("hard_rock", "rock"),
            "classic rock" to setOf("classic_rock", "rock"),
            "alternative rock" to setOf("alternative_rock", "rock"),
            "punk rock" to setOf("punk_rock", "rock"),
            "heavy metal" to setOf("heavy_metal", "metal"),
            "thrash metal" to setOf("thrash_metal", "metal"),
            "k pop" to setOf("k_pop", "pop"),
            "j pop" to setOf("j_pop", "pop"),
            "j rock" to setOf("rock"),
        )

    private val languageByNormalizedTag: Map<String, String> =
        buildMap {
            for (lang in languages) {
                put(normalizeTag(lang.id), lang.id)
                put(normalizeTag(lang.displayName), lang.id)
                for (tag in lang.lastFmTags) {
                    val key = normalizeTag(tag)
                    if (key !in this) put(key, lang.id)
                }
            }
        }

    private val directTagToGenreIds: Map<String, Set<String>> =
        buildMap<String, MutableSet<String>> {
            for (genre in genres) {
                val ids = mutableSetOf(genre.id)
                genre.parentId?.let { ids += it }
                fun register(raw: String) {
                    val norm = normalizeTag(raw)
                    if (norm.isNotBlank()) {
                        getOrPut(norm) { mutableSetOf() }.addAll(ids)
                    }
                }
                register(genre.id)
                register(genre.displayName.substringBefore(" ("))
                genre.lastFmTags.forEach(::register)
            }
            for ((phrase, ids) in multiWordCompoundPhrases) {
                val norm = normalizeTag(phrase)
                getOrPut(norm) { mutableSetOf() }.addAll(ids)
            }
        }

    fun normalizeTag(raw: String): String =
        raw
            .trim()
            .lowercase()
            .replace('-', ' ')
            .replace('_', ' ')
            .replace('/', ' ')
            .replace(Regex("""\s+"""), " ")

    fun languageOption(idOrCustom: String): SpaceLanguageOption? {
        val norm = normalizeTag(idOrCustom)
        return languagesById[idOrCustom.lowercase()]
            ?: languages.firstOrNull { normalizeTag(it.displayName) == norm || norm in it.searchKeywords.map(::normalizeTag) }
    }

    fun genreOption(idOrCustom: String): SpaceGenreOption? {
        val norm = normalizeTag(idOrCustom)
        return genresById[idOrCustom.lowercase()]
            ?: genres.firstOrNull {
                normalizeTag(it.displayName.substringBefore(" (")) == norm ||
                    normalizeTag(it.displayName) == norm
            }
    }

    fun languageDisplayName(idOrCustom: String): String =
        languageOption(idOrCustom)?.displayName
            ?: idOrCustom.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun genreDisplayName(idOrCustom: String): String =
        genreOption(idOrCustom)?.displayName
            ?: idOrCustom.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun searchLanguages(query: String): List<SpaceLanguageOption> {
        val trimmed = normalizeTag(query)
        if (trimmed.isBlank()) return languages.take(12)
        val exact = mutableListOf<SpaceLanguageOption>()
        val prefix = mutableListOf<SpaceLanguageOption>()
        val contains = mutableListOf<SpaceLanguageOption>()
        for (option in languages) {
            val name = normalizeTag(option.displayName)
            val keywords = option.searchKeywords.map(::normalizeTag)
            when {
                name == trimmed || keywords.any { it == trimmed } -> exact += option
                name.startsWith(trimmed) || keywords.any { it.startsWith(trimmed) } -> prefix += option
                name.contains(trimmed) || keywords.any { it.contains(trimmed) } -> contains += option
            }
        }
        return (exact + prefix + contains).distinctBy { it.id }
    }

    /**
     * Searches curated genres with smart parent/subgenre expansion.
     * For example, typing "soundtracks" returns:
     *   1. Soundtracks (All)
     *   2. Video Game Soundtracks
     *   3. Movie Soundtracks
     */
    fun searchGenres(query: String): List<SpaceGenreOption> {
        val trimmed = normalizeTag(query)
        if (trimmed.isBlank()) {
            // Show primary/parent genres first by default.
            return genres.filter { it.parentId == null }
        }
        val matched = mutableListOf<SpaceGenreOption>()
        val exactParents = mutableListOf<SpaceGenreOption>()
        val prefix = mutableListOf<SpaceGenreOption>()
        val contains = mutableListOf<SpaceGenreOption>()

        for (option in genres) {
            val cleanName = normalizeTag(option.displayName.substringBefore(" ("))
            val keywords = option.searchKeywords.map(::normalizeTag)
            val isExact = cleanName == trimmed || keywords.any { it == trimmed }
            val isPrefix = cleanName.startsWith(trimmed) || keywords.any { it.startsWith(trimmed) }
            val isContains = cleanName.contains(trimmed) || keywords.any { it.contains(trimmed) }
            when {
                isExact && option.parentId == null -> exactParents += option
                isExact -> prefix += option
                isPrefix -> prefix += option
                isContains -> contains += option
            }
        }

        // Whenever a parent genre matches strongly, immediately group its sub-options right beneath it.
        for (parent in exactParents) {
            matched += parent
            matched += childGenresByParent[parent.id].orEmpty()
        }
        for (item in prefix) {
            if (matched.none { it.id == item.id }) {
                matched += item
                if (item.parentId == null) {
                    matched += childGenresByParent[item.id].orEmpty().filter { child -> matched.none { it.id == child.id } }
                }
            }
        }
        for (item in contains) {
            if (matched.none { it.id == item.id }) {
                matched += item
                if (item.parentId == null) {
                    matched += childGenresByParent[item.id].orEmpty().filter { child -> matched.none { it.id == child.id } }
                }
            }
        }
        return matched.distinctBy { it.id }
    }

    fun isNoiseTag(rawTag: String): Boolean {
        val norm = normalizeTag(rawTag)
        if (norm.isBlank() || norm.length <= 1) return true
        if (norm in noiseTags) return true
        // Decade / year tags like "2010s", "1998"
        if (norm.matches(Regex("""\d{2,4}s?"""))) return true
        return false
    }

    /**
     * Resolves a single Last.fm tag into canonical language ID(s), if any.
     */
    fun detectLanguageFromTag(rawTag: String): String? {
        val norm = normalizeTag(rawTag)
        if (norm.isBlank()) return null
        languageByNormalizedTag[norm]?.let { return it }
        // Check token prefixes/suffixes like "japanese rock", "korean indie", "spanish pop", "malayalam film"
        val words = norm.split(' ')
        if (words.size >= 2) {
            for (word in words) {
                val lang = languageByNormalizedTag[word]
                // Avoid matching generic genre words like "funk" inside unrelated tags
                if (lang != null && word != "funk" && word != "piano" && word != "classical" && word != "ambient") {
                    return lang
                }
            }
        }
        return null
    }

    /**
     * Detects canonical genre IDs represented by a Last.fm tag, including:
     * - Direct & alias matches
     * - Parent inheritance (e.g. "video game music" -> soundtracks_video_game AND soundtracks)
     * - Compound splitting (e.g. "pop rock" -> pop AND rock)
     */
    fun detectGenresFromTag(rawTag: String): Set<String> {
        val norm = normalizeTag(rawTag)
        if (norm.isBlank() || isNoiseTag(norm)) return emptySet()

        val result = mutableSetOf<String>()
        directTagToGenreIds[norm]?.let { result.addAll(it) }

        // Check multi-word compound phrases contained in the tag.
        for ((phrase, genreIds) in multiWordCompoundPhrases) {
            if (norm == phrase || norm.contains(" $phrase") || norm.contains("$phrase ")) {
                result.addAll(genreIds)
            }
        }

        // Split compound tags into tokens (e.g. "pop rock", "indie folk pop", "rap rock").
        val tokens = norm.split(' ').filter { it.isNotBlank() }
        for (token in tokens) {
            compoundTokenToGenreIds[token]?.let { result.addAll(it) }
        }

        // Propagate child genres up to their parent (e.g. synth_pop -> pop, soundtracks_video_game -> soundtracks).
        val parents = result.mapNotNull { genresById[it]?.parentId }
        result.addAll(parents)

        return result
    }

    /**
     * Checks whether a set of raw Last.fm tags satisfies a specific requested genre ID (or custom genre tag),
     * respecting parent/child hierarchy, compound tags, and subgenre exclusions.
     */
    fun tagsMatchRequestedGenre(
        rawTags: Collection<String>,
        requestedGenre: String,
    ): Boolean {
        val normalizedTags = rawTags.map(::normalizeTag).filter { it.isNotBlank() }
        if (normalizedTags.isEmpty()) return false

        val option = genreOption(requestedGenre)
        if (option == null) {
            // Custom user-entered genre tag: match exact, substring word match, or normalized token overlap.
            val target = normalizeTag(requestedGenre)
            if (target.isBlank()) return false
            return normalizedTags.any { tag ->
                tag == target || tag.contains(target) || target.contains(tag) && tag.length >= 3
            }
        }

        // Enforce subgenre exclusions (e.g. pure video game soundtrack tags shouldn't match Movie Soundtracks).
        if (option.excludedTags.isNotEmpty()) {
            val hasExcluded = normalizedTags.any { it in option.excludedTags }
            val hasExplicitOwn = normalizedTags.any { it in option.lastFmTags }
            if (hasExcluded && !hasExplicitOwn) return false
        }

        // Special-case strictness for Soundtrack subgenres so generic "soundtrack" alone doesn't match
        // Video Game Soundtracks unless there is a video game signal.
        if (option.id == "soundtracks_video_game") {
            return normalizedTags.any { tag ->
                tag in option.lastFmTags ||
                    tag.contains("video game") ||
                    tag.contains("videogame") ||
                    tag.contains("game ost") ||
                    tag.contains("game soundtrack") ||
                    tag == "vgm"
            }
        }
        if (option.id == "soundtracks_movie") {
            val hasVideoGameSignal =
                normalizedTags.any { tag ->
                    tag in option.excludedTags || tag.contains("video game") || tag.contains("videogame") || tag == "vgm"
                }
            if (hasVideoGameSignal) return false
            return normalizedTags.any { tag ->
                tag in option.lastFmTags ||
                    tag.contains("movie") ||
                    tag.contains("film") ||
                    tag.contains("cinema") ||
                    tag.contains("motion picture") ||
                    tag == "soundtrack" ||
                    tag == "ost"
            }
        }

        val acceptedIds = buildSet {
            add(option.id)
            childGenresByParent[option.id]?.forEach { add(it.id) }
        }

        return normalizedTags.any { tag ->
            val detected = detectGenresFromTag(tag)
            detected.any { it in acceptedIds }
        }
    }

    /**
     * Detects non-Latin scripts from text (title + artists).
     * Returns canonical language IDs strongly implied by the Unicode script.
     */
    fun detectLanguagesFromScript(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        var hasHiraganaOrKatakana = false
        var hasHangul = false
        var hasHan = false
        var hasDevanagari = false
        var hasMalayalam = false
        var hasTamil = false
        var hasTelugu = false
        var hasGurmukhi = false
        var hasArabic = false
        var hasCyrillic = false
        var hasThai = false

        for (ch in text) {
            val code = ch.code
            when (code) {
                in 0x3040..0x30FF, in 0x31F0..0x31FF -> hasHiraganaOrKatakana = true
                in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> hasHangul = true
                in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> hasHan = true
                in 0x0900..0x097F -> hasDevanagari = true
                in 0x0D00..0x0D7F -> hasMalayalam = true
                in 0x0B80..0x0BFF -> hasTamil = true
                in 0x0C00..0x0C7F -> hasTelugu = true
                in 0x0A00..0x0A7F -> hasGurmukhi = true
                in 0x0600..0x06FF, in 0x0750..0x077F -> hasArabic = true
                in 0x0400..0x04FF -> hasCyrillic = true
                in 0x0E00..0x0E7F -> hasThai = true
            }
        }

        return buildSet {
            if (hasHiraganaOrKatakana) add("japanese")
            if (hasHangul) add("korean")
            if (hasHan && !hasHiraganaOrKatakana && !hasHangul) add("chinese")
            if (hasDevanagari) add("hindi")
            if (hasMalayalam) add("malayalam")
            if (hasTamil) add("tamil")
            if (hasTelugu) add("telugu")
            if (hasGurmukhi) add("punjabi")
            if (hasArabic) add("arabic")
            if (hasCyrillic) add("russian")
            if (hasThai) add("thai")
        }
    }

    /**
     * Strong Latin-script language indicators for titles/artists when scripts alone are Latin.
     * Kept high-precision so English titles aren't falsely flagged.
     */
    fun detectLatinLanguageHints(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val lower = text.lowercase()
        val words = extractWords(lower)
        val result = mutableSetOf<String>()

        // Unique punctuation/diacritics
        if (lower.contains('¿') || lower.contains('¡') || lower.contains('ñ')) {
            result += "spanish"
        }
        if (lower.contains('ß')) {
            result += "german"
        }
        if (lower.any { it in "ăâđêôơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ" }) {
            result += "vietnamese"
        }
        if (lower.any { it in "ğış" }) {
            result += "turkish"
        }
        if (lower.any { it in "ąćęłńśźż" }) {
            result += "polish"
        }
        if (lower.contains('ã') || lower.contains('õ')) {
            result += "portuguese"
        }

        // High-precision multi-word stopword checks (require >= 2 language stopwords to avoid false positives)
        fun countMatches(markers: Set<String>): Int = words.count { it in markers }

        if (countMatches(SPANISH_STOPWORDS) >= 2) result += "spanish"
        if (countMatches(FRENCH_STOPWORDS) >= 2) result += "french"
        if (countMatches(GERMAN_STOPWORDS) >= 2) result += "german"
        if (countMatches(PORTUGUESE_STOPWORDS) >= 2) result += "portuguese"
        if (countMatches(ITALIAN_STOPWORDS) >= 2) result += "italian"
        if (countMatches(HINDI_ROMANIZED_MARKERS) >= 2 || lower.contains("bollywood") || lower.contains("t-series")) {
            result += "hindi"
        }
        if (lower.contains("malayalam") || lower.contains("mollywood")) result += "malayalam"
        if (lower.contains("tamil") || lower.contains("kollywood")) result += "tamil"
        if (lower.contains("telugu") || lower.contains("tollywood")) result += "telugu"
        if (lower.contains("punjabi") || lower.contains("bhangra")) result += "punjabi"

        return result
    }

    fun hasStrongEnglishWordSignal(text: String): Boolean {
        val words = extractWords(text.lowercase())
        return words.any { it in ENGLISH_STRONG_WORDS }
    }

    private fun extractWords(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (ch.isLetter() || ch == '\'') {
                current.append(ch)
            } else if (current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
        }
        if (current.isNotEmpty()) {
            result += current.toString()
        }
        return result
    }

    fun isPureLatinText(text: String): Boolean =
        text.any { it.isLetter() } && detectLanguagesFromScript(text).isEmpty()

    /**
     * Finds YouTube Home chips closest to the requested genres (e.g. "Relax", "Energize", "Party", "Focus", "Pop", "Hip-hop").
     */
    fun findMatchingChips(
        chips: List<Chip>,
        genres: List<String>,
    ): List<Chip> {
        if (chips.isEmpty() || genres.isEmpty()) return emptyList()
        val desiredHints =
            genres
                .flatMap { genreId ->
                    val option = genreOption(genreId)
                    if (option != null) {
                        listOf(option.displayName.substringBefore(" (")) + option.ytChipHints + option.searchKeywords.take(3)
                    } else {
                        listOf(genreId)
                    }
                }.map(::normalizeTag)
                .distinct()

        return chips
            .mapNotNull { chip ->
                val chipTitle = normalizeTag(chip.title)
                if (chipTitle.isBlank()) return@mapNotNull null
                val score =
                    when {
                        desiredHints.any { it == chipTitle } -> 3
                        desiredHints.any { chipTitle.contains(it) || it.contains(chipTitle) } -> 2
                        else -> 0
                    }
                if (score > 0) chip to score else null
            }.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.title.lowercase() }
    }

    /**
     * Finds YouTube Music "Moods & genres" BrowseItems matching the Space's selected genres.
     */
    fun findMatchingMoodItems(
        items: List<BrowseItem>,
        genres: List<String>,
    ): List<BrowseItem> {
        if (items.isEmpty() || genres.isEmpty()) return emptyList()
        val genreTerms =
            genres
                .flatMap { genreId ->
                    val option = genreOption(genreId)
                    if (option != null) {
                        listOf(option.displayName.substringBefore(" (")) + option.searchKeywords + option.ytChipHints
                    } else {
                        listOf(genreId)
                    }
                }.map(::normalizeTag)
                .distinct()

        return items
            .mapNotNull { item ->
                val title = normalizeTag(item.title)
                if (title.isBlank()) return@mapNotNull null
                val score =
                    when {
                        genreTerms.any { it == title } -> 3
                        genreTerms.any { title.contains(it) || it.contains(title) } -> 2
                        else -> 0
                    }
                if (score > 0) item to score else null
            }.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { "${it.id}:${it.params.orEmpty()}" }
    }

    /**
     * Builds targeted YouTube Music search queries from the Space's languages and genres.
     */
    fun buildFallbackSearchQueries(filter: SpaceFilterSpec): List<String> {
        val normalized = filter.normalized()
        if (!normalized.isActive) return emptyList()

        val langTerms =
            normalized.languages.map { langId ->
                languageOption(langId)?.searchQueryTerm ?: langId.trim().lowercase()
            }
        val genreTerms =
            normalized.genres.map { genreId ->
                genreOption(genreId)?.searchQueryTerm ?: genreId.trim().lowercase()
            }

        val queries = linkedSetOf<String>()
        when {
            langTerms.isNotEmpty() && genreTerms.isNotEmpty() -> {
                for (lang in langTerms.take(2)) {
                    for (genre in genreTerms.take(2)) {
                        queries += "$lang $genre songs"
                        queries += "$lang $genre"
                    }
                }
            }
            langTerms.isNotEmpty() -> {
                for (lang in langTerms.take(3)) {
                    queries += "$lang songs"
                    queries += "best $lang music"
                }
            }
            genreTerms.isNotEmpty() -> {
                for (genre in genreTerms.take(3)) {
                    queries += "$genre songs"
                    queries += "best $genre music"
                }
            }
        }
        return queries.toList().take(4)
    }

    fun formatFilterSummary(filter: SpaceFilterSpec): String {
        val normalized = filter.normalized()
        val parts =
            normalized.languages.map(::languageDisplayName) +
                normalized.genres.map { genreDisplayName(it).substringBefore(" (") }
        return parts.joinToString(" · ")
    }

    private val SPANISH_STOPWORDS =
        setOf("el", "los", "las", "del", "con", "por", "para", "sin", "pero", "como", "cuando", "donde", "quiero", "corazon", "corazón", "noche", "contigo", "siempre", "amor", "vida", "estoy", "eres")

    private val FRENCH_STOPWORDS =
        setOf("je", "tu", "nous", "vous", "avec", "pour", "dans", "sans", "mais", "comme", "tout", "tous", "une", "des", "les", "aux", "suis", "est", "sont", "c'est", "mon", "ton", "amour", "nuit", "coeur", "cœur")

    private val GERMAN_STOPWORDS =
        setOf("ich", "du", "wir", "ihr", "und", "nicht", "mit", "für", "fur", "von", "auf", "aus", "ein", "eine", "der", "die", "das", "den", "dem", "mein", "dein", "liebe", "nacht", "herz")

    private val PORTUGUESE_STOPWORDS =
        setOf("não", "nao", "você", "voce", "com", "para", "pelo", "pela", "dos", "das", "uma", "meu", "minha", "teu", "saudade", "coração", "coracao", "noite", "tudo", "mais")

    private val ITALIAN_STOPWORDS =
        setOf("che", "con", "per", "una", "degli", "delle", "sono", "sei", "siamo", "tutto", "sempre", "cuore", "notte", "amore", "senza", "quando", "anche")

    private val HINDI_ROMANIZED_MARKERS =
        setOf("dil", "pyaar", "pyar", "ishq", "tera", "teri", "mera", "meri", "tum", "tumhare", "mein", "hain", "hai", "kya", "nahi", "zindagi", "mohabbat", "yaad", "raat", "khuda", "sanam", "jaane", "kabhi")

    private val ENGLISH_STRONG_WORDS =
        setOf(
            "the", "and", "you", "your", "with", "from", "that", "this", "what", "when",
            "where", "never", "always", "heart", "night", "love", "world", "time", "life",
            "back", "down", "over", "into", "about", "like", "know", "don't", "can't",
            "won't", "i'm", "it's", "we're", "they", "there", "here", "still", "more",
        )
}

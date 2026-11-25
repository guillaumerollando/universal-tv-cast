package studio.rollando.castor.data

import java.text.Normalizer

/**
 * Chaînes connues, reconnues dans l'adresse ou le titre du flux : leur vrai nom et leur couleur de marque.
 * Codées à l'avance (le flux ne dit ni son nom ni sa couleur) ; une chaîne inconnue reçoit une couleur tirée de
 * son nom. Quelques kilo-octets pour ~150 chaînes : autant en connaître beaucoup.
 */
object Channels {
    data class Known(val name: String, val color: Long)

    private fun k(name: String, color: Long) = Known(name, color)

    // Mot-clé (lettres et chiffres, sans accent) → chaîne. Les plus précis d'abord (« tv5mondeinfo » avant « tv5monde »).
    // Un mot-clé de moins de 4 caractères doit être un mot entier de l'adresse ou du titre.
    private val LIST: List<Pair<String, Known>> = listOf(
        // France — TNT et info
        "tf1seriesfilms" to k("TF1 Séries Films", 0xFF6A2C91), "tf1" to k("TF1", 0xFF1C3F94),
        "france2" to k("France 2", 0xFFE4002B), "france3" to k("France 3", 0xFF1F5AA8),
        "france4" to k("France 4", 0xFF6E2C8F), "france5" to k("France 5", 0xFF00A24E),
        "franceo" to k("France Ô", 0xFF00A0DF), "francetv" to k("france.tv", 0xFF1F1F1F),
        "franceinfo" to k("franceinfo:", 0xFFF5A300), "france24" to k("France 24", 0xFF0E70B6),
        "canalplus" to k("Canal+", 0xFF1D1D1B), "canal" to k("Canal+", 0xFF1D1D1B),
        "arte" to k("Arte", 0xFFFA481C), "m6" to k("M6", 0xFF4A4A4A), "6ter" to k("6ter", 0xFF8E1F6B),
        "w9" to k("W9", 0xFF7B2D8E), "tmc" to k("TMC", 0xFF0072BC), "tfx" to k("TFX", 0xFFE5007E),
        "nrj12" to k("NRJ 12", 0xFFD50032), "c8" to k("C8", 0xFF5B2A86), "cstar" to k("CStar", 0xFFEF7D00),
        "cnews" to k("CNews", 0xFFD6001C), "bfmtv" to k("BFMTV", 0xFF0A3D8F), "bfmbusiness" to k("BFM Business", 0xFF0A3D8F),
        "bfmparis" to k("BFM Paris", 0xFF0A3D8F), "bfm" to k("BFMTV", 0xFF0A3D8F), "rmcstory" to k("RMC Story", 0xFF00A3E0),
        "rmcdecouverte" to k("RMC Découverte", 0xFF00873E), "rmcsport" to k("RMC Sport", 0xFF1D1D1B), "rmc" to k("RMC", 0xFF1D1D1B),
        "lci" to k("LCI", 0xFF233C8C), "lequipe" to k("L'Équipe", 0xFFE30613), "gulli" to k("Gulli", 0xFFF39200),
        "cherie25" to k("Chérie 25", 0xFFE6007E), "t18" to k("T18", 0xFF2B2B2B), "novo19" to k("Novo 19", 0xFF2B2B2B),
        "publicsenat" to k("Public Sénat", 0xFFB3122E), "lcp" to k("LCP", 0xFF003A70), "lcpan" to k("LCP", 0xFF003A70),
        "parisprem" to k("Paris Première", 0xFF1D1D1B), "teva" to k("Téva", 0xFFE5007E), "rtl9" to k("RTL9", 0xFFD6001C),
        "mcm" to k("MCM", 0xFF1D1D1B), "planeteplus" to k("Planète+", 0xFF00A3E0), "ushuaia" to k("Ushuaïa TV", 0xFF00873E),
        "histoiretv" to k("Histoire TV", 0xFF7A1E1E), "toutelhistoire" to k("Toute l'Histoire", 0xFF7A1E1E),
        "museumtv" to k("Museum TV", 0xFF1D1D1B), "kto" to k("KTO", 0xFF00539F), "franceinter" to k("France Inter", 0xFFE2001A),
        "bsmart" to k("B Smart", 0xFF1D1D1B), "tv7" to k("TV7 Bordeaux", 0xFFE30613),
        // Jeunesse
        "tiji" to k("TiJi", 0xFFF39200), "canalj" to k("Canal J", 0xFF00A3E0), "piwi" to k("Piwi+", 0xFF7AB800),
        "teletoon" to k("Télétoon+", 0xFFE5007E), "boomerang" to k("Boomerang", 0xFF00A3E0), "cartoon" to k("Cartoon Network", 0xFF1D1D1B),
        "disney" to k("Disney Channel", 0xFF1A4FA0), "nickelodeon" to k("Nickelodeon", 0xFFFF6600), "nickjr" to k("Nick Jr.", 0xFFFF6600),
        "okoo" to k("Okoo", 0xFF7B2D8E), "lumni" to k("Lumni", 0xFF00A3E0),
        // Musique
        "mtv" to k("MTV", 0xFF1D1D1B), "m6music" to k("M6 Music", 0xFF4A4A4A), "nrjhits" to k("NRJ Hits", 0xFFD50032),
        "tracetv" to k("Trace", 0xFF1D1D1B), "traceurban" to k("Trace Urban", 0xFF1D1D1B), "mezzo" to k("Mezzo", 0xFF7A1E1E), "stingray" to k("Stingray", 0xFF1D1D1B),
        "melodytv" to k("Melody", 0xFFB8860B), "virginradio" to k("Virgin Radio TV", 0xFFE30613), "rfm" to k("RFM TV", 0xFF00A3E0),
        // Sport
        "eurosport" to k("Eurosport", 0xFF141B4D), "beinsports" to k("beIN Sports", 0xFF5B2A86), "bein" to k("beIN Sports", 0xFF5B2A86),
        "infosport" to k("Infosport+", 0xFF1D1D1B), "automoto" to k("Automoto", 0xFFE30613), "golfplus" to k("Golf+", 0xFF00873E),
        "equidia" to k("Equidia", 0xFF00873E), "olympics" to k("Olympic Channel", 0xFF0081C8), "redbull" to k("Red Bull TV", 0xFF1E2A5A),
        "fifa" to k("FIFA+", 0xFF326295), "dazn" to k("DAZN", 0xFF1D1D1B), "ligue1" to k("Ligue 1+", 0xFF0B1F44),
        // Belgique
        "rtbf" to k("RTBF", 0xFFE30613), "laune" to k("La Une", 0xFFE30613), "tipik" to k("Tipik", 0xFF00A3E0),
        "latrois" to k("La Trois", 0xFF6E2C8F), "rtltvi" to k("RTL TVI", 0xFFD6001C), "clubrtl" to k("Club RTL", 0xFFD6001C),
        "plugrtl" to k("Plug RTL", 0xFFD6001C), "ln24" to k("LN24", 0xFF0A3D8F), "bx1" to k("BX1", 0xFFE5007E),
        "vrt" to k("VRT", 0xFF5A5A5A), "een" to k("VRT 1", 0xFF5A5A5A), "vtm" to k("VTM", 0xFFE30613),
        // Suisse
        "rts" to k("RTS", 0xFFE30613), "rtsun" to k("RTS 1", 0xFFE30613), "rtsdeux" to k("RTS 2", 0xFFE30613),
        "srf" to k("SRF", 0xFFAF0D1A), "rsi" to k("RSI", 0xFF00539F), "lemanbleu" to k("Léman Bleu", 0xFF00A3E0),
        "tvm3" to k("TVM3", 0xFFE5007E), "teleticino" to k("TeleTicino", 0xFF00539F),
        // Canada / Québec
        "radiocanada" to k("ICI Radio-Canada", 0xFF1D1D1B), "rdi" to k("ICI RDI", 0xFFE30613), "icitele" to k("ICI Télé", 0xFF1D1D1B),
        "xcanrdi" to k("ICI RDI", 0xFFE30613), "xcancbft" to k("ICI Télé", 0xFF1D1D1B), "amdici" to k("ICI Montréal", 0xFF1D1D1B),
        "tva" to k("TVA", 0xFF00539F), "lcn" to k("LCN", 0xFF00539F), "noovo" to k("Noovo", 0xFFF5A300),
        "telequebec" to k("Télé-Québec", 0xFF00A3E0), "savoir" to k("Savoir Média", 0xFF7AB800), "cbc" to k("CBC", 0xFFE30613),
        "ctv" to k("CTV", 0xFF1D1D1B), "cpac" to k("CPAC", 0xFF00539F), "tfo" to k("TFO", 0xFF00A3E0),
        // Luxembourg, Monaco, Afrique francophone
        "rtllu" to k("RTL Lëtzebuerg", 0xFFD6001C), "tvmonaco" to k("TV Monaco", 0xFFE30613), "mcrtv" to k("TV Monaco", 0xFFE30613),
        "monacoinfo" to k("MonacoInfo", 0xFFE30613), "rti" to k("RTI", 0xFFF39200), "ortm" to k("ORTM", 0xFF00873E),
        "rts1" to k("RTS Sénégal", 0xFF00873E), "2m" to k("2M", 0xFFE30613), "alaoula" to k("Al Aoula", 0xFF00873E),
        "medi1" to k("Medi1 TV", 0xFF00539F), "canal2" to k("Canal 2", 0xFF00539F), "crtv" to k("CRTV", 0xFF00873E),
        "tv5mondeinfo" to k("TV5Monde Info", 0xFF0091D2), "tv5mondeafrique" to k("TV5Monde Afrique", 0xFF0091D2),
        "tv5monde" to k("TV5Monde", 0xFF0091D2), "tv5" to k("TV5Monde", 0xFF0091D2),
        // Info internationale
        "euronews" to k("Euronews", 0xFF1E4FA3), "cgtn" to k("CGTN", 0xFF0C4C8A), "africanews" to k("Africanews", 0xFFC8102E),
        "aljazeera" to k("Al Jazeera", 0xFFC8902E), "dw" to k("DW", 0xFF05B2FC), "bbcnews" to k("BBC News", 0xFFB80000),
        "bbc" to k("BBC", 0xFF1D1D1B), "cnn" to k("CNN", 0xFFCC0000), "skynews" to k("Sky News", 0xFFC8102E),
        "foxnews" to k("Fox News", 0xFF003366), "msnbc" to k("MSNBC", 0xFF1D1D1B), "nbc" to k("NBC", 0xFF1D1D1B),
        "abcnews" to k("ABC News", 0xFF1D1D1B), "cbsnews" to k("CBS News", 0xFF1D1D1B), "bloomberg" to k("Bloomberg", 0xFF1D1D1B),
        "trtworld" to k("TRT World", 0xFFE30613), "nhk" to k("NHK World", 0xFFE30613), "rtve" to k("RTVE", 0xFFE30613),
        "rai" to k("Rai", 0xFF00539F), "zdf" to k("ZDF", 0xFFFA7D19), "ard" to k("Das Erste", 0xFF00539F), "tagesschau" to k("tagesschau24", 0xFF00539F),
        "i24" to k("i24news", 0xFF1D1D1B), "france24arabic" to k("France 24", 0xFF0E70B6), "rtl" to k("RTL", 0xFFD6001C),
        "nasa" to k("NASA TV", 0xFF0B3D91), "pluto" to k("Pluto TV", 0xFF1D1D1B), "rakuten" to k("Rakuten TV", 0xFFBF0000),
        "samsungtvplus" to k("Samsung TV Plus", 0xFF1428A0),
    )

    private val PALETTE = listOf(0xFF2E6F8E, 0xFF7A3E9D, 0xFF2F8F6B, 0xFFB5523B, 0xFF3A5BA0, 0xFF8E6A2E, 0xFF9C2F5A)

    /** Couleur d'une chaîne inconnue : tirée de son nom, toujours la même. */
    fun colorFor(name: String): Long = PALETTE[(name.hashCode() and 0x7fffffff) % PALETTE.size]

    private fun plain(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace("+", "plus")

    /** Chaîne reconnue dans [text] (adresse et titre du flux), ou null. « France 2 » comme « france-2 » ou « france2 ». */
    fun find(text: String): Known? {
        val words = plain(text).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        // Mots seuls, et mots collés deux ou trois à la suite (« france » « 2 » → « france2 »).
        val tokens = words + words.zipWithNext { a, b -> a + b } + words.windowed(3) { it.joinToString("") }
        for ((kw, known) in LIST) if (tokens.any { it == kw || (kw.length >= 4 && it.startsWith(kw)) }) return known
        return null
    }
}

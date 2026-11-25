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

    // International. Les plus précis d'abord ; jamais un mot courant seul (« one », « news », « sport »…).
    private val WORLD: List<Pair<String, Known>> = listOf(
        // Royaume-Uni, Irlande
        "bbcone" to k("BBC One", 0xFFB80000), "bbctwo" to k("BBC Two", 0xFF6B1A7A), "bbcthree" to k("BBC Three", 0xFFE5007E),
        "bbcfour" to k("BBC Four", 0xFF1D1D1B), "bbcnews" to k("BBC News", 0xFFB80000), "bbcworld" to k("BBC World News", 0xFFB80000),
        "cbbc" to k("CBBC", 0xFF7AB800), "cbeebies" to k("CBeebies", 0xFFF5A300), "bbciplayer" to k("BBC iPlayer", 0xFFE5007E),
        "itv1" to k("ITV1", 0xFF00A3E0), "itv2" to k("ITV2", 0xFF00A3E0), "itvx" to k("ITVX", 0xFF00A3E0), "itv" to k("ITV", 0xFF00A3E0),
        "channel4" to k("Channel 4", 0xFF1D1D1B), "channel5" to k("Channel 5", 0xFFE30613), "e4" to k("E4", 0xFF6B1A7A),
        "film4" to k("Film4", 0xFF1D1D1B), "gbnews" to k("GB News", 0xFF0A2D6E), "talktv" to k("TalkTV", 0xFFE30613),
        "rte" to k("RTÉ", 0xFF00873E), "tg4" to k("TG4", 0xFF1D1D1B), "s4c" to k("S4C", 0xFF00A3E0),
        // États-Unis
        "abcnews" to k("ABC News", 0xFF1D1D1B), "abcaustralia" to k("ABC Australia", 0xFF1D1D1B), "abc" to k("ABC", 0xFF1D1D1B), "cbs" to k("CBS", 0xFF1D1D1B), "foxnews" to k("Fox News", 0xFF003366),
        "foxbusiness" to k("Fox Business", 0xFF003366), "foxsports" to k("Fox Sports", 0xFF003366), "fox" to k("FOX", 0xFF1D1D1B),
        "pbs" to k("PBS", 0xFF2638C4), "cnbc" to k("CNBC", 0xFF005594), "newsmax" to k("Newsmax", 0xFF003366),
        "espn" to k("ESPN", 0xFFE30613), "hbo" to k("HBO", 0xFF1D1D1B), "amc" to k("AMC", 0xFF1D1D1B), "tnt" to k("TNT", 0xFF1D1D1B),
        "comedycentral" to k("Comedy Central", 0xFFF5A300), "nationalgeographic" to k("National Geographic", 0xFFFFCE00),
        "natgeo" to k("National Geographic", 0xFFFFCE00), "discoverychannel" to k("Discovery", 0xFF00539F), "discoveryplus" to k("discovery+", 0xFF00539F), "historychannel" to k("History", 0xFF1D1D1B),
        "animalplanet" to k("Animal Planet", 0xFF00873E), "weatherchannel" to k("The Weather Channel", 0xFF00539F),
        "cspan" to k("C-SPAN", 0xFF00539F), "nasatv" to k("NASA TV", 0xFF0B3D91), "pbskids" to k("PBS Kids", 0xFF7AB800),
        "telemundo" to k("Telemundo", 0xFFE30613), "univision" to k("Univision", 0xFF00A3E0), "newsy" to k("Newsy", 0xFF1D1D1B),
        "scrippsnews" to k("Scripps News", 0xFF00539F), "livenow" to k("LiveNOW from FOX", 0xFF003366), "cheddar" to k("Cheddar", 0xFFF5A300),
        "tubi" to k("Tubi", 0xFFFF6600), "roku" to k("The Roku Channel", 0xFF6C3C97), "xumo" to k("Xumo", 0xFF7B2D8E),
        // Allemagne, Autriche
        "daserste" to k("Das Erste", 0xFF00539F), "zdfneo" to k("ZDFneo", 0xFFFA7D19), "zdfinfo" to k("ZDFinfo", 0xFFFA7D19),
        "3sat" to k("3sat", 0xFF1D1D1B), "phoenixde" to k("phoenix", 0xFF1D1D1B), "kika" to k("KiKA", 0xFF7AB800),
        "rtlzwei" to k("RTLzwei", 0xFFD6001C), "ntv" to k("n-tv", 0xFFE30613), "prosieben" to k("ProSieben", 0xFFE30613),
        "sat1" to k("SAT.1", 0xFF00539F), "kabeleins" to k("Kabel Eins", 0xFF00539F), "vox" to k("VOX", 0xFFE30613),
        "weltde" to k("WELT", 0xFF00539F), "welttv" to k("WELT", 0xFF00539F), "br24" to k("BR", 0xFF00539F), "wdr" to k("WDR", 0xFF00539F), "ndr" to k("NDR", 0xFF00539F),
        "mdr" to k("MDR", 0xFF00539F), "swr" to k("SWR", 0xFF00539F), "hrfernsehen" to k("hr", 0xFF00539F), "rbb" to k("rbb", 0xFFE30613),
        "orf1" to k("ORF 1", 0xFFE30613), "orf2" to k("ORF 2", 0xFFE30613), "orf" to k("ORF", 0xFFE30613), "servustv" to k("ServusTV", 0xFF1D1D1B),
        // Espagne
        "la1" to k("La 1", 0xFFE30613), "la2" to k("La 2", 0xFFE30613), "24h" to k("Canal 24 Horas", 0xFFE30613),
        "antena3" to k("Antena 3", 0xFFF39200), "lasexta" to k("laSexta", 0xFF7AB800), "cuatro" to k("Cuatro", 0xFF1D1D1B),
        "telecinco" to k("Telecinco", 0xFF00539F), "tv3cat" to k("TV3", 0xFF1D1D1B), "3cat" to k("3Cat", 0xFF1D1D1B),
        "etb" to k("EITB", 0xFF00873E), "eitb" to k("EITB", 0xFF00873E), "canalsur" to k("Canal Sur", 0xFF00873E),
        "telemadrid" to k("Telemadrid", 0xFFE30613), "apunt" to k("À Punt", 0xFFF39200), "tvg" to k("TVG", 0xFF00A3E0),
        "clantv" to k("Clan", 0xFF7AB800), "teledeporte" to k("Teledeporte", 0xFF00539F),
        // Italie
        "rai1" to k("Rai 1", 0xFF00539F), "rai2" to k("Rai 2", 0xFF00539F), "rai3" to k("Rai 3", 0xFF00539F),
        "rainews" to k("Rai News 24", 0xFF00539F), "raisport" to k("Rai Sport", 0xFF00539F), "raiplay" to k("RaiPlay", 0xFF00539F),
        "canale5" to k("Canale 5", 0xFF00539F), "italia1" to k("Italia 1", 0xFFE30613), "rete4" to k("Rete 4", 0xFF00539F),
        "la7" to k("La7", 0xFF00539F), "tv8" to k("TV8", 0xFF1D1D1B), "skytg24" to k("Sky TG24", 0xFF00539F),
        "tgcom24" to k("TGCom24", 0xFFE30613), "mediaset" to k("Mediaset", 0xFF00539F),
        // Portugal, Brésil
        "rtp1" to k("RTP1", 0xFF00873E), "rtp3" to k("RTP3", 0xFF00873E), "rtp" to k("RTP", 0xFF00873E), "sic" to k("SIC", 0xFFE30613),
        "sicnoticias" to k("SIC Notícias", 0xFFE30613), "tvi" to k("TVI", 0xFFE30613), "cnnportugal" to k("CNN Portugal", 0xFFCC0000),
        "globo" to k("Globo", 0xFF00539F), "globonews" to k("GloboNews", 0xFF00539F), "sbt" to k("SBT", 0xFF00539F),
        "recordtv" to k("Record", 0xFF00539F), "recordnews" to k("Record News", 0xFF00539F), "bandtv" to k("Band", 0xFF00539F), "bandnews" to k("BandNews", 0xFF00539F), "tvcultura" to k("TV Cultura", 0xFF1D1D1B),
        "cnnbrasil" to k("CNN Brasil", 0xFFCC0000), "redetv" to k("RedeTV!", 0xFF00539F),
        // Pays-Bas, Nordiques
        "npo1" to k("NPO 1", 0xFFFF6600), "npo2" to k("NPO 2", 0xFFFF6600), "npo3" to k("NPO 3", 0xFFFF6600), "npo" to k("NPO", 0xFFFF6600),
        "rtl4" to k("RTL 4", 0xFFD6001C), "nosjournaal" to k("NOS", 0xFFE30613), "nosnl" to k("NOS", 0xFFE30613), "svt" to k("SVT", 0xFF1D1D1B), "nrk" to k("NRK", 0xFF1D1D1B),
        "yle" to k("Yle", 0xFF00A3E0), "dr1" to k("DR1", 0xFFE30613), "tv2" to k("TV 2", 0xFFE30613),
        // Europe centrale et de l'Est, Grèce, Turquie
        "tvp" to k("TVP", 0xFF00539F), "tvn24" to k("TVN24", 0xFFE30613), "polsat" to k("Polsat", 0xFFF39200),
        "ct24" to k("ČT24", 0xFF00539F), "rtvslo" to k("RTV SLO", 0xFF00539F), "hrt" to k("HRT", 0xFF00539F), "rts1srb" to k("RTS", 0xFF00539F),
        "ert" to k("ERT", 0xFF00539F), "skai" to k("SKAI", 0xFF00539F), "trt" to k("TRT", 0xFFE30613), "trthaber" to k("TRT Haber", 0xFFE30613),
        "ntvtr" to k("NTV", 0xFFE30613), "haberturk" to k("Habertürk", 0xFFE30613), "showtv" to k("Show TV", 0xFF1D1D1B),
        "ukraine24" to k("Ukraine 24", 0xFF00539F), "suspilne" to k("Suspilne", 0xFF1D1D1B), "mtva" to k("M1", 0xFF00539F),
        "protv" to k("Pro TV", 0xFF00539F), "digi24" to k("Digi24", 0xFF00539F), "tvr" to k("TVR", 0xFF00539F),
        // Monde arabe, Iran, Israël
        "aljazeeramubasher" to k("Al Jazeera Mubasher", 0xFFC8902E), "alarabiya" to k("Al Arabiya", 0xFF00539F),
        "alhadath" to k("Al Hadath", 0xFFE30613), "skynewsarabia" to k("Sky News Arabia", 0xFFC8102E), "mbc" to k("MBC", 0xFF00539F),
        "rotana" to k("Rotana", 0xFFB8860B), "dmc" to k("DMC", 0xFF1D1D1B), "alghad" to k("Alghad TV", 0xFFE30613),
        "aloula" to k("Al Aoula", 0xFF00873E), "ennahar" to k("Ennahar TV", 0xFFE30613), "echorouk" to k("Echorouk", 0xFF00539F),
        "elwatania" to k("El Watania", 0xFFE30613), "kan11" to k("Kan 11", 0xFF1D1D1B), "iranintl" to k("Iran International", 0xFF00539F),
        "presstv" to k("Press TV", 0xFFE30613),
        // Asie, Océanie
        "nhkworld" to k("NHK World", 0xFFE30613), "kbs" to k("KBS", 0xFF00539F), "arirang" to k("Arirang", 0xFF00539F),
        "ytn" to k("YTN", 0xFF00539F), "mbn" to k("MBN", 0xFFE30613), "tvbs" to k("TVBS", 0xFF00539F), "ctitv" to k("CTi", 0xFFE30613),
        "cctv" to k("CCTV", 0xFFE30613), "phoenixtv" to k("Phoenix TV", 0xFFF39200), "channelnewsasia" to k("CNA", 0xFFE30613),
        "cna" to k("CNA", 0xFFE30613), "wion" to k("WION", 0xFF00539F), "ndtv" to k("NDTV", 0xFFE30613), "aajtak" to k("Aaj Tak", 0xFFE30613),
        "indiatoday" to k("India Today", 0xFFE30613), "ddnews" to k("DD News", 0xFF00539F), "republicworld" to k("Republic", 0xFF1D1D1B), "republictv" to k("Republic", 0xFF1D1D1B),
        "zeenews" to k("Zee News", 0xFF6B1A7A), "geonews" to k("Geo News", 0xFFE30613), "aryn" to k("ARY News", 0xFFE30613),
        "sbs" to k("SBS", 0xFFF5A300), "tvnz" to k("TVNZ", 0xFF1D1D1B),
        "abscbn" to k("ABS-CBN", 0xFF00539F), "gma" to k("GMA", 0xFFE30613), "thaipbs" to k("Thai PBS", 0xFF00539F), "vtv" to k("VTV", 0xFFE30613),
        // Amériques latines
        "tvpublica" to k("TV Pública", 0xFF00A3E0), "telefe" to k("Telefe", 0xFF00539F), "c5n" to k("C5N", 0xFFE30613),
        "todonoticias" to k("TN", 0xFF00539F), "canal13" to k("Canal 13", 0xFF00539F), "24horas" to k("24 Horas", 0xFFE30613),
        "tvn" to k("TVN", 0xFFE30613), "televisa" to k("Televisa", 0xFF1D1D1B), "azteca" to k("TV Azteca", 0xFF00873E),
        "milenio" to k("Milenio", 0xFF1D1D1B), "caracol" to k("Caracol", 0xFF00539F), "rcn" to k("RCN", 0xFFE30613),
        "telesur" to k("teleSUR", 0xFFE30613), "ntn24" to k("NTN24", 0xFFE30613), "dwespanol" to k("DW Español", 0xFF05B2FC),
        "cnnenespanol" to k("CNN en Español", 0xFFCC0000), "cnnespanol" to k("CNN en Español", 0xFFCC0000), "ecuavisa" to k("Ecuavisa", 0xFF00539F), "latinatv" to k("Latina", 0xFF00539F),
        // Afrique
        "sabc" to k("SABC", 0xFF00539F), "enca" to k("eNCA", 0xFFE30613), "newzroom" to k("Newzroom Afrika", 0xFFE30613),
        "nta" to k("NTA", 0xFF00873E), "channelstv" to k("Channels TV", 0xFF00539F), "arisenews" to k("Arise News", 0xFF1D1D1B), "arisetv" to k("Arise News", 0xFF1D1D1B),
        "citizentv" to k("Citizen TV", 0xFFE30613), "ntvkenya" to k("NTV Kenya", 0xFFE30613), "kbc" to k("KBC", 0xFF00539F),
        "ebc" to k("EBC", 0xFF00539F), "ghanatv" to k("GTV", 0xFF00873E), "joynews" to k("Joy News", 0xFFE30613),
        // International (anglais), documentaires, divertissement
        "cnninternational" to k("CNN International", 0xFFCC0000), "euronewsenglish" to k("Euronews", 0xFF1E4FA3),
        "france24english" to k("France 24", 0xFF0E70B6), "dwenglish" to k("DW", 0xFF05B2FC), "aljazeeraenglish" to k("Al Jazeera English", 0xFFC8902E),
        "trtworld" to k("TRT World", 0xFFE30613), "cgtnamerica" to k("CGTN America", 0xFF0C4C8A), "rtenews" to k("RTÉ News", 0xFF00873E),
        "bloombergtv" to k("Bloomberg", 0xFF1D1D1B), "reuters" to k("Reuters", 0xFFFF8000), "apnews" to k("AP", 0xFF1D1D1B),
        "eurosport1" to k("Eurosport 1", 0xFF141B4D), "eurosport2" to k("Eurosport 2", 0xFF141B4D), "skysports" to k("Sky Sports", 0xFFE30613),
        "btsport" to k("TNT Sports", 0xFF1D1D1B), "tntsports" to k("TNT Sports", 0xFF1D1D1B), "nbcsports" to k("NBC Sports", 0xFF1D1D1B),
        "wwe" to k("WWE", 0xFF1D1D1B), "nfl" to k("NFL", 0xFF013369), "nba" to k("NBA", 0xFF1D428A), "mlb" to k("MLB", 0xFF041E42),
        "nhl" to k("NHL", 0xFF1D1D1B), "f1tv" to k("F1 TV", 0xFFE10600), "motorvision" to k("Motorvision", 0xFFE30613),
        "fashiontv" to k("Fashion TV", 0xFF1D1D1B), "ftv" to k("Fashion TV", 0xFF1D1D1B), "travelxp" to k("Travelxp", 0xFF00A3E0),
        "insighttv" to k("Insight TV", 0xFF1D1D1B), "docubox" to k("Docubox", 0xFF1D1D1B), "curiositystream" to k("Curiosity", 0xFF00539F),
        "smithsonian" to k("Smithsonian", 0xFFF5A300), "bloombergquint" to k("Bloomberg", 0xFF1D1D1B), "skyarts" to k("Sky Arts", 0xFF6B1A7A),
        "skynewsau" to k("Sky News Australia", 0xFFC8102E), "skynews" to k("Sky News", 0xFFC8102E),
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
        for ((kw, known) in WORLD + LIST) if (tokens.any { it == kw || (kw.length >= 4 && it.startsWith(kw)) }) return known
        return null
    }
}

package com.vasik96.spojeuk

// Všechny ladicí konstanty na jednom místě. Zdroje poloh mají své URL u sebe (PortaboSource, DpmulSource).

/** Jednotná verze aplikace (BuildConfig.VERSION_NAME z build.gradle.kts / gradle.properties). */
val APP_VERSION: String get() = BuildConfig.VERSION_NAME

val USER_AGENT = "spojeuk/$APP_VERSION (Android)"

// ── jízdní řády (GTFS z Sap1k/obehy) ──
const val RELEASES_API = "https://api.github.com/repos/Sap1k/obehy/releases/latest"
const val ASSET_NAME = "jdf-filtered.tar"

/** Předpočítané GTFS jen pro oblast (workflow region-gtfs.yml ve spojeuk-data): ~5 MB místo ~120 MB. */
const val REGION_BASE_URL = "https://github.com/Vasik96/spojeuk-data/releases/download/gtfs"

/** Vlaky (CZPTT) z téhož releasu: linky S1/R22, čísla vlaků a tvary tras. Stahuje se jen gtfs.zip uvnitř. */
const val RAIL_ASSET_NAME = "czptt.tar"

/** Oblast, ze které se do databáze berou spoje. */
val BBOX_LAT = 50.45..50.85
val BBOX_LON = 13.75..14.40

/** Oblast pro vlaky: celý Ústecký kraj s okolím (spoj se bere, když se jí dotkne, a to celý, třeba až do Prahy).
 *  Drž v souladu s tools/build_train_shapes.py. */
val RAIL_BBOX_LAT = 50.10..51.10
val RAIL_BBOX_LON = 12.90..14.60

// ── vyhledávání spojení ──
const val FOOT_MAX_M = 250.0          // max. pěší přesun mezi zastávkami
const val WALK_MS = 1.1               // rychlost chůze v m/s
const val MIN_TRANSFER = 120          // min. čas na přestup v s
const val NEXT_DAY_CUTOFF = 6 * 3600  // spoje po půlnoci, které se ještě berou jako "dnešní noc"

// ── DPMÚL ──
/** Části názvu dopravce (bez diakritiky, malými písmeny), podle kterých se pozná MHD v Ústí. */
val DPMUL_AGENCY_KEYWORDS = listOf("dopravni podnik mesta usti", "dpmul")

const val SERVER_IP = "http://37.46.211.70:8787"
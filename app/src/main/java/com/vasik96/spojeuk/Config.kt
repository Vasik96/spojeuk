package com.vasik96.spojeuk

// Všechny ladicí konstanty na jednom místě. Zdroje poloh mají své URL u sebe (PortaboSource, DpmulSource).

const val USER_AGENT = "spojeuk/1.6 (Android)"

// ── jízdní řády (GTFS z Sap1k/obehy) ──
const val RELEASES_API = "https://api.github.com/repos/Sap1k/obehy/releases/latest"
const val ASSET_NAME = "jdf-filtered.tar"

/** Oblast, ze které se do databáze berou spoje. */
val BBOX_LAT = 50.45..50.85
val BBOX_LON = 13.75..14.40

// ── vyhledávání spojení ──
const val FOOT_MAX_M = 250.0          // max. pěší přesun mezi zastávkami
const val WALK_MS = 1.1               // rychlost chůze v m/s
const val MIN_TRANSFER = 120          // min. čas na přestup v s
const val NEXT_DAY_CUTOFF = 6 * 3600  // spoje po půlnoci, které se ještě berou jako "dnešní noc"

// ── DPMÚL ──
/** Části názvu dopravce (bez diakritiky, malými písmeny), podle kterých se pozná MHD v Ústí. */
val DPMUL_AGENCY_KEYWORDS = listOf("dopravni podnik mesta usti", "dpmul")

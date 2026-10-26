package com.girish.expensetracker

/** App-wide constants. Nothing about the owner lives here: accounts, cards, names and salary day come from settings. */
object UserConfig {
    /** How far back the first-run import reads the SMS inbox. */
    const val IMPORT_DAYS = 120L
}

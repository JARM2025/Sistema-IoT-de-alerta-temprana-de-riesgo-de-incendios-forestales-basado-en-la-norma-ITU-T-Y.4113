plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// --- Force Vico to 2.2.0 across all configurations ---
allprojects {
    configurations.all {
        resolutionStrategy {
            eachDependency {
                if (requested.group == "com.patrykandpatrick.vico") {
                    useVersion("2.2.0")
                    because("Align all Vico modules to 2.2.0 to use the 2.x API (HorizontalAxis/VerticalAxis, etc.).")
                }
            }
            force(
                "com.patrykandpatrick.vico:compose:2.2.0",
                "com.patrykandpatrick.vico:compose-m3:2.2.0",
                "com.patrykandpatrick.vico:core:2.2.0"
            )
        }
    }
}


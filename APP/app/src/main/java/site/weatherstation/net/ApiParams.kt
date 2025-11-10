package site.weatherstation.net

enum class Agg(val wire: String) {
    RAW("raw"), MIN("min"), MAX("max"), MEAN("mean"), MEDIAN("median"), SUM("sum"), COUNT("count")
}

enum class Fill(val wire: String) {
    NONE("none"), PREVIOUS("previous"), LINEAR("linear"), ZERO("zero")
}

enum class Order(val wire: String) {
    ASC("ASC"), DESC("DESC")
}

enum class Interval(val wire: String) {
    S10("10s"), S30("30s"), M1("1m"), M5("5m"), M10("10m"), M30("30m"), H1("1h")
}

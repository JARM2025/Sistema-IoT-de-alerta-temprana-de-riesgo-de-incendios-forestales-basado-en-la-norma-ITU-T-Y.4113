package site.weatherstation.util

import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun prettyErrorMessage(t: Throwable): String = when (t) {
    is UnknownHostException -> "No internet connection or DNS unresolved."
    is SocketTimeoutException -> "Request timed out while connecting to the server."
    is IOException -> "Network I/O error. Check your connection."
    is HttpException -> {
        val code = t.code()
        when (code) {
            400 -> "Invalid request (400)."
            401 -> "Unauthorized (401)."
            403 -> "Forbidden (403)."
            404 -> "Resource not found (404)."
            429 -> "Too many requests (429)."
            in 500..599 -> "Server error ($code)."
            else -> "HTTP error ($code)."
        }
    }
    else -> "Unexpected error: ${t.message ?: t.toString()}"
}

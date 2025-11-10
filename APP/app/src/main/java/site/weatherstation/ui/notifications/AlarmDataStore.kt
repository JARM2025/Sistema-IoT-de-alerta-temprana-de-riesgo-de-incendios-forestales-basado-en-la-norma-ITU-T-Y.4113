package site.weatherstation.ui.notifications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.alarmDataStore by preferencesDataStore(name = "alarm_prefs")

object AlarmDataStore {
    private object Keys {
        val ALARMS_JSON = stringPreferencesKey("alarms_json")
    }

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val listType = Types.newParameterizedType(List::class.java, Alarm::class.java)
    private val adapter = moshi.adapter<List<Alarm>>(listType)

    /** Flujo de la lista de alarmas persistidas. */
    fun alarmsFlow(context: Context): Flow<List<Alarm>> =
        context.alarmDataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs ->
                val json = prefs[Keys.ALARMS_JSON].orEmpty()
                if (json.isBlank()) emptyList()
                else runCatching { adapter.fromJson(json) ?: emptyList() }.getOrDefault(emptyList())
            }

    /** Lee una vez la lista de alarmas. */
    suspend fun getAlarms(context: Context): List<Alarm> {
        val prefs = try {
            context.alarmDataStore.data.first()
        } catch (e: IOException) {
            emptyPreferences()
        }
        val json = prefs[Keys.ALARMS_JSON].orEmpty()
        return if (json.isBlank()) emptyList()
        else runCatching { adapter.fromJson(json) ?: emptyList() }.getOrDefault(emptyList())
    }

    /** Guarda la lista de alarmas (sobrescribe). */
    suspend fun setAlarms(context: Context, alarms: List<Alarm>) {
        val json = runCatching { adapter.toJson(alarms) }.getOrDefault("[]")
        context.alarmDataStore.edit { prefs ->
            prefs[Keys.ALARMS_JSON] = json
        }
    }
}

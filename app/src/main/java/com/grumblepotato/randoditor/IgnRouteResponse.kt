package com.grumblepotato.randoditor

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import com.google.gson.annotations.SerializedName

// IGN API
data class IgnRouteResponse(
    @SerializedName("geometry") val geometry: RouteGeometry?,
    @SerializedName("distance") val distance: Double?,
    @SerializedName("duration") val duration: Double?
)

data class RouteGeometry(
    @SerializedName("coordinates") val coordinates: List<List<Double>>
)

// Retrofit interface for IGN
interface IgnRoutingService {
    @GET("itineraire")
    suspend fun getRoute(
        @Query("resource") resource: String = "bdtopo-osrm",
        @Query("profile") profile: String = "pedestrian", // car, pedestrian, bike
        @Query("optimization") optimization: String = "fastest",
        @Query("start") start: String,
        @Query("end") end: String,
        @Query("geometryFormat") geometryFormat: String = "geojson"
    ): IgnRouteResponse

    companion object {
        private const val BASE_URL = "https://data.geopf.fr/navigation/"

        fun create(): IgnRoutingService {
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            return retrofit.create(IgnRoutingService::class.java)
        }
    }
}
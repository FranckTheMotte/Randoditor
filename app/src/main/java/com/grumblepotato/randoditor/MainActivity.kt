package com.grumblepotato.randoditor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.grumblepotato.randoditor.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var routingService: IgnRoutingService

    // application states
    private enum class RouteMode {
        VIEW,
        FREE_DRAWING,
        GUIDED_ROUTING
    }

    private var currentMode = RouteMode.VIEW
    private val routePoints = mutableListOf<GeoPoint>()
    private var currentPolyline: Polyline? = null
    private val markers = mutableListOf<Marker>()

    // Permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            setupMap()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init routing service
        routingService = IgnRoutingService.create()

        checkPermissionsAndSetup()
        setupButtons()
    }

    private fun checkPermissionsAndSetup() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) ==
                        PackageManager.PERMISSION_GRANTED
            }) {
            setupMap()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun setupMap() {
        binding.mapView.apply {
            // Basic configuration
            setMultiTouchControls(true)

            // IGN
            setTileSource(object : OnlineTileSourceBase(
                "IGN-Plan",
                0, 18, 256, ".jpeg",
                arrayOf(
                    "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws"+
                            "&SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"+
                            "&LAYER=GEOGRAPHICALGRIDSYSTEMS.MAPS.SCAN25TOUR" +
                            "&STYLE=normal&TILEMATRIXSET=PM" +
                            "&TILEMATRIX=%d&TILEROW=%d&TILECOL=%d&FORMAT=image/jpeg"
                )

            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    return baseUrl.format(
                        MapTileIndex.getZoom(pMapTileIndex),
                        MapTileIndex.getY(pMapTileIndex),
                        MapTileIndex.getX(pMapTileIndex)
                    )
                }
            })

            // Center on Font-Romeu
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(42.506059, 2.038907, 1.888334))
        }

        setupMapClickListener()
    }

    private fun setupMapClickListener() {
        val mapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                when (currentMode) {
                    RouteMode.FREE_DRAWING -> addFreePoint(p)
                    RouteMode.GUIDED_ROUTING -> addGuidedPoint(p)
                    RouteMode.VIEW -> return false
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                return false
            }
        }

        val mapEventsOverlay = MapEventsOverlay(mapEventsReceiver)
        binding.mapView.overlays.add(0, mapEventsOverlay)
    }

    private fun setupButtons() {
        binding.btnFreeRoute.setOnClickListener {
            toggleMode(RouteMode.FREE_DRAWING)
        }

        binding.btnGuidedRoute.setOnClickListener {
            toggleMode(RouteMode.GUIDED_ROUTING)
        }

        binding.btnLoadGpx.setOnClickListener {
            // TODO: implement load
            Toast.makeText(this, "Load GPX TODO", Toast.LENGTH_SHORT).show()
        }

        binding.btnUndo.setOnClickListener {
            undoLastPoint()
        }

        binding.btnClear.setOnClickListener {
            clearRoute()
        }

        binding.btnSave.setOnClickListener {
            saveRouteAsGpx()
        }
    }

    private fun toggleMode(newMode: RouteMode) {
        if (currentMode == newMode) {
            // Désactiver le mode
            currentMode = RouteMode.VIEW
            binding.tvMode.text = "Mode: Affichage"
            binding.btnFreeRoute.isEnabled = true
            binding.btnGuidedRoute.isEnabled = true
        } else {
            // Activer le nouveau mode
            clearRoute()
            currentMode = newMode
            when (newMode) {
                RouteMode.FREE_DRAWING -> {
                    binding.tvMode.text = "Mode: Tracé libre (cliquez sur la carte)"
                    binding.btnFreeRoute.isEnabled = false
                    binding.btnGuidedRoute.isEnabled = true
                }
                RouteMode.GUIDED_ROUTING -> {
                    binding.tvMode.text = "Mode: Tracé guidé (cliquez pour ajouter des points)"
                    binding.btnFreeRoute.isEnabled = true
                    binding.btnGuidedRoute.isEnabled = false
                }
                RouteMode.VIEW -> {}
            }
        }
        updateButtonStates()
    }

    private fun addFreePoint(point: GeoPoint) {
        routePoints.add(point)
        addMarker(point, routePoints.size)
        updatePolyline()
        updateButtonStates()
        updateInfo()
    }

    private fun addGuidedPoint(point: GeoPoint) {
        if (routePoints.isEmpty()) {
            // Premier point
            routePoints.add(point)
            addMarker(point, 1)
            updateButtonStates()
        } else {
            // Calculer le routing depuis le dernier point
            val lastPoint = routePoints.last()
            calculateRoute(lastPoint, point)
        }
    }

    private fun calculateRoute(start: GeoPoint, end: GeoPoint) {
        binding.progressBar.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            try {
                val startStr = "${start.longitude},${start.latitude}"
                val endStr = "${end.longitude},${end.latitude}"

                val response = withContext(Dispatchers.IO) {
                    routingService.getRoute(
                        start = startStr,
                        end = endStr,
                        profile = "pedestrian"
                    )
                }

                response.geometry?.coordinates?.let { coords ->
                    // Convertir les coordonnées (format: [lon, lat])
                    val newPoints = coords.map { coord ->
                        GeoPoint(coord[1], coord[0])
                    }

                    // Ajouter les nouveaux points (sauf le premier qui existe déjà)
                    routePoints.addAll(newPoints.drop(1))
                    addMarker(end, routePoints.size)
                    updatePolyline()
                    updateInfo()
                    updateButtonStates()
                }

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Erreur routing: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                e.printStackTrace()
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun addMarker(point: GeoPoint, number: Int) {
        val marker = Marker(binding.mapView).apply {
            position = point
            title = "Point $number"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        markers.add(marker)
        binding.mapView.overlays.add(marker)
        binding.mapView.invalidate()
    }

    private fun updatePolyline() {
        // Supprimer l'ancienne ligne
        currentPolyline?.let { binding.mapView.overlays.remove(it) }

        // Créer la nouvelle ligne
        if (routePoints.size >= 2) {
            currentPolyline = Polyline(binding.mapView).apply {
                setPoints(routePoints)
                outlinePaint.color = Color.RED
                outlinePaint.strokeWidth = 8f
            }
            binding.mapView.overlays.add(currentPolyline)
        }

        binding.mapView.invalidate()
    }

    private fun undoLastPoint() {
        if (routePoints.isNotEmpty()) {
            routePoints.removeLast()

            if (markers.isNotEmpty()) {
                val lastMarker = markers.removeLast()
                binding.mapView.overlays.remove(lastMarker)
            }

            updatePolyline()
            updateInfo()
            updateButtonStates()
        }
    }

    private fun clearRoute() {
        routePoints.clear()

        currentPolyline?.let { binding.mapView.overlays.remove(it) }
        currentPolyline = null

        markers.forEach { binding.mapView.overlays.remove(it) }
        markers.clear()

        binding.mapView.invalidate()
        updateInfo()
        updateButtonStates()
    }

    private fun updateButtonStates() {
        val hasPoints = routePoints.isNotEmpty()
        binding.btnUndo.isEnabled = hasPoints
        binding.btnClear.isEnabled = hasPoints
        binding.btnSave.isEnabled = hasPoints
    }

    private fun updateInfo() {
        val distance = calculateDistance()
        binding.tvInfo.text = "Distance: %.2f km | Points: %d".format(
            distance / 1000.0,
            routePoints.size
        )
    }

    private fun calculateDistance(): Double {
        if (routePoints.size < 2) return 0.0

        var totalDistance = 0.0
        for (i in 0 until routePoints.size - 1) {
            totalDistance += routePoints[i].distanceToAsDouble(routePoints[i + 1])
        }
        return totalDistance
    }

    private fun saveRouteAsGpx() {
        // TODO: Implémenter la sauvegarde GPX
        Toast.makeText(this, "Sauvegarde GPX à implémenter", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }
}

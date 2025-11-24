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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var routingService: IgnRoutingService

    // application states
    private enum class RouteMode {
        VIEW,
        FREE_DRAWING,
        GUIDED_ROUTING
    }

    // Class to keep added segments
    private data class RouteSegment(
        val points: List<GeoPoint>,
        val marker: Marker
    )

    private var currentMode = RouteMode.VIEW
    private val routePoints = mutableListOf<GeoPoint>()
    private val routeSegments = mutableListOf<RouteSegment>()
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
            // Disable mode
            currentMode = RouteMode.VIEW
            binding.tvMode.text = "Mode: Display"
            binding.btnFreeRoute.isEnabled = true
            binding.btnGuidedRoute.isEnabled = true
        } else {
            // Activate the new mode
            clearRoute()
            currentMode = newMode
            when (newMode) {
                RouteMode.FREE_DRAWING -> {
                    binding.tvMode.text = "Mode: Free drawing (click on map)"
                    binding.btnFreeRoute.isEnabled = false
                    binding.btnGuidedRoute.isEnabled = true
                }
                RouteMode.GUIDED_ROUTING -> {
                    binding.tvMode.text = "Mode: Guided drawing (add points on route)"
                    binding.btnFreeRoute.isEnabled = true
                    binding.btnGuidedRoute.isEnabled = false
                }
                RouteMode.VIEW -> {}
            }
        }
        updateButtonStates()
    }

    private fun addFreePoint(point: GeoPoint) {
        val marker = addMarker(point, markers.size + 1)

        // For the first point, a segment with a single point is created otherwise a line
        val segmentPoints = if (routePoints.isEmpty()) {
            listOf(point)
        } else {
            listOf(routePoints.last(), point)
        }

        routePoints.add(point)
        routeSegments.add(RouteSegment(segmentPoints, marker))

        updatePolyline()
        updateButtonStates()
        updateInfo()
    }

    private fun addGuidedPoint(point: GeoPoint) {
        if (routePoints.isEmpty()) {
            // First point
            val marker = addMarker(point, 1)
            routePoints.add(point)
            routeSegments.add(RouteSegment(listOf(point), marker))
            updateButtonStates()
        } else {
            // Calculate routing from last point
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
                    // Geographical coords convert
                    val newPoints = coords.map { coord ->
                        GeoPoint(coord[1], coord[0])
                    }

                    // Marker for the end point
                    val marker = addMarker(end, markers.size + 1)

                    // Add a new points
                    val segmentPoints = newPoints.drop(1)
                    routePoints.addAll(segmentPoints)
                    routeSegments.add(RouteSegment(newPoints, marker))

                    updatePolyline()
                    updateInfo()
                    updateButtonStates()
                }

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Routing error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                e.printStackTrace()
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private fun addMarker(point: GeoPoint, number: Int): Marker {
        val marker = Marker(binding.mapView).apply {
            position = point
            title = "Point $number"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        markers.add(marker)
        binding.mapView.overlays.add(marker)
        binding.mapView.invalidate()
        return marker
    }

    private fun updatePolyline() {
        // Remove last line
        currentPolyline?.let { binding.mapView.overlays.remove(it) }

        // Create the new line
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
        if (routeSegments.isEmpty()) return

        // Retrieve the last segment
        val lastSegment = routeSegments.removeLast()

        // Delete the linked marker
        binding.mapView.overlays.remove(lastSegment.marker)
        markers.remove(lastSegment.marker)

        // Remove segment's points
        // Free route: remove 1 point
        // Guided route: remove all points between 2 last marker
        val pointsToRemove = if (currentMode == RouteMode.FREE_DRAWING) {
            1
        } else {
            lastSegment.points.size - 1 // -1 because the first point belong to previous segment
        }

        repeat(pointsToRemove) {
            if (routePoints.isNotEmpty()) {
                routePoints.removeLast()
            }
        }

        updatePolyline()
        updateInfo()
        updateButtonStates()
    }

    private fun clearRoute() {
        routePoints.clear()
        routeSegments.clear()

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
        Toast.makeText(this, "TODO implement", Toast.LENGTH_SHORT).show()
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

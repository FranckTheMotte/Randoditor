package com.grumblepotato.randoditor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.preference.PreferenceManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.grumblepotato.randoditor.databinding.ActivityMainBinding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        // osmdroid configuration
        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissionsAndSetup()

        binding.btnLoadGpx.setOnClickListener {
            // TODO: add load of a gpx file
            loadSampleGpxTrace()
        }
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
    }

    private fun loadSampleGpxTrace() {
        // TODO: load a gpx file
        // Draw a line
        val points = listOf(
            GeoPoint(42.506059, 2.038907),
            GeoPoint(42.501766, 2.045395),
            GeoPoint(42.49984, 2.037465)
        )

        drawTrackOnMap(points)
    }

    private fun drawTrackOnMap(points: List<GeoPoint>) {
        val line = Polyline(binding.mapView).apply {
            setPoints(points)
            outlinePaint.color = android.graphics.Color.BLUE
            outlinePaint.strokeWidth = 8f
        }

        binding.mapView.overlays.add(line)
        binding.mapView.invalidate()

        // Center on trace
        if (points.isNotEmpty()) {
            binding.mapView.controller.setCenter(points[0])
            binding.mapView.controller.setZoom(16.0)
        }
    }

    /**
     * Load a gpx file and display on current map.
     * @param inputStream
     */
    private fun loadGpxFile(inputStream: java.io.InputStream) {
        try {
            val gpx = io.jenetics.jpx.GPX.Reader.DEFAULT.read(inputStream)
            val allPoints = mutableListOf<GeoPoint>()

            // Browse the tracks
            gpx.tracks().forEach { track ->
                track.segments().forEach { segment ->
                    segment.points().forEach { point ->
                        allPoints.add(
                            GeoPoint(
                                point.latitude.toDegrees(),
                                point.longitude.toDegrees()
                            )
                        )
                    }
                }
            }

            // browse the routes
            gpx.routes().forEach { route ->
                route.points().forEach { point ->
                    allPoints.add(
                        GeoPoint(
                            point.latitude.toDegrees(),
                            point.longitude.toDegrees()
                        )
                    )
                }
            }

            if (allPoints.isNotEmpty()) {
                drawTrackOnMap(allPoints)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            // TODO: handle error
        }
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

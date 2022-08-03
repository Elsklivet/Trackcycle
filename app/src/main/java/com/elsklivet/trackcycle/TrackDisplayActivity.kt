package com.elsklivet.trackcycle

import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.elsklivet.trackcycle.databinding.ActivityTrackDisplayBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

class TrackDisplayActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var binding: ActivityTrackDisplayBinding

    // Location Services
    private lateinit var fusedLocationClient: FusedLocationProviderClient;

    // Coordinates
    private var coords: ArrayList<LatLng> = ArrayList()

    // Distance total
    private var distance: Float = 0.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTrackDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "Trip History"

        // val coordsSize: Int? = intent.extras?.get("size") as Int?

        // if (coordsSize != null) {
        //     var i = 0
        //     while (i < coordsSize && intent.hasExtra("lat$i") && intent.hasExtra("lng$i")) {
        //         // Intents are awful and I hate them
        //         val lat = intent.extras!!.get("lat$i") as Double
        //         val lng = intent.extras!!.get("lng$i") as Double
        //         i++
        //         coords.add(LatLng(lat,lng))
        //     }
        // }

        // val args = intent.getBundleExtra("BUNDLE")
        val newCoords = intent.getParcelableArrayListExtra<LocationWrapper>("LOCATIONS")
        if (newCoords != null) {
            var last: Location? = null
            for (coord in newCoords) {
                if (last != null) {
                    // Do distance calc
                    var newLoc = Location(LocationManager.GPS_PROVIDER)
                    newLoc.latitude = coord.latLng[0]
                    newLoc.longitude = coord.latLng[1]
                    distance += last.distanceTo(newLoc)
                }
                coords.add(LatLng(coord.latLng[0], coord.latLng[1]))
                last = Location(LocationManager.GPS_PROVIDER)
                last.latitude = coord.latLng[0]
                last.longitude = coord.latLng[1]
            }
        }

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        if (coords.isEmpty()) {
            Toast.makeText(this, "Somehow received empty coordinate vector", Toast.LENGTH_SHORT)
                .show()
            return
        }

        // test
        // coords.add(LatLng(coords[0].latitude - 1, coords[0].longitude + 1))

        var unit = "m"
        if (distance >= 1000f) {
            distance /= 1000f
            unit = "km"
        }
        mMap.addMarker(MarkerOptions().position(coords[0]).visible(true).title("Starting location"))
        mMap.addMarker(
            MarkerOptions().position(coords[coords.lastIndex]).visible(true)
                .title("Travelled ${distance.toString()} $unit")
        )
        // another test
        // mMap.addPolyline(PolylineOptions().add(coords[0]).add(LatLng(coords[0].latitude + 1, coords[0].longitude + 1)).color(Color.GREEN).width(5f))

        // Draw polyline
        mMap.addPolyline(PolylineOptions().addAll(coords).color(Color.RED).width(5f))

        mMap.moveCamera(CameraUpdateFactory.newLatLng(coords[0]))
    }
}
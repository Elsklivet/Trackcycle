package com.elsklivet.trackcycle

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.*
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Time
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.elsklivet.trackcycle.databinding.FragmentFirstBinding
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.io.FileWriter
import kotlin.math.abs
import kotlin.math.sqrt


/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment(),
    GoogleApiClient.ConnectionCallbacks,
    GoogleApiClient.OnConnectionFailedListener,
    LocationListener,
    SensorEventListener {

    private var _binding: FragmentFirstBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    // Activity
    private lateinit var mActivity: MainActivity

    // Intervals
    private var primaryInterval = 1000L;
    private var fastestInterval = 1000L;

    // Kalman Processor
    // private val kalmanProcessor = KalmanProcessor()

    // Location Services
    private lateinit var locationManager: LocationManager

    // private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationProvider: LocationProvider? = null
    private lateinit var gnssStatusCallback: GnssStatus.Callback
    private lateinit var locationListener: LocationListener

    // Google Sign-In Client
    // private lateinit var mSignInClient: GoogleSignInClient
    // Google API Client
    private lateinit var mGoogleApiClient: GoogleApiClient

    // Location Request Binding
    // private lateinit var mLocationRequest: LocationRequest

    // Current Location
    private var mCurrentLocation: Location? = null

    // Current Processed Location
    // private var mProcessedLocation: LocationKt? = null

    // GPS Listening
    private var mGPSListening: Boolean = false

    // GPS Acquired Fix
    private var mGPSFirstFix: Boolean = false

    // Services Connected
    private var mServicesConnected: Boolean = false

    // XYZ from Accelerometer
    private var mLinearAccel = FloatArray(3)
    private var mLinearAccelLast = FloatArray(3)

    // XYZ from Accelerometer
    private var mGyroRot = FloatArray(3)
    private var mGyroLast = FloatArray(3)

    // XYZ from Magnetometer
    private var mMagneto = FloatArray(3)
    private var mMagnetoLast = FloatArray(3)

    // XYZ from Raw Accelerometer
    private var mAccelRaw = FloatArray(3)
    private var mAccelRawLast = FloatArray(3)

    // Compass Orientation Matrices
    private var rotationMatrix = FloatArray(9)
    private var orientationAngles = FloatArray(3)

    // Alpha(s)
    private var alpha = 0.1f

    // Sensor Manager
    private lateinit var sensorManager: SensorManager

    // (Linear) Accelerometer
    private var accelerometer: Sensor? = null

    // Raw Accelerometer
    private var accelerometerRaw: Sensor? = null

    // Gyroscope
    private var gyroscope: Sensor? = null

    // Magnetometer
    private var magnetometer: Sensor? = null

    // CSV Lines Data
    private val csvData: MutableList<String> = ArrayList()

    // Battery Manager
    private lateinit var batteryManager: BatteryManager

    // Battery Levels
    private var mBatteryLevel: Int = 0
    private var mBatteryScale: Int = 1
    private var mBatteryPct: Float = 0.0f
    private var mBatteryMicroAmps: Int = 0

    // Battery Receiver
    private var mBatInfoReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context, intent: Intent) {
            mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            mBatteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            mBatteryPct = mBatteryLevel * 100 / mBatteryScale.toFloat()
            mBatteryMicroAmps =
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }
    }

    // Duty Cycling Flag
    private var mDutyCyclingEnabled: Boolean = true

    // CONSTANTS
    private val GPS_START_TIME = 14L * 1000L
    private val GPS_CYCLE_SAVE_THRESHOLD_TIME = 10L * 1000L
    private val GPS_CYCLE_OFF_TIME = GPS_START_TIME + GPS_CYCLE_SAVE_THRESHOLD_TIME
    private val AZIMUTH_TRIGGER_DIFFERENCE = 60
    private val NUM_POINTS_TO_AVERAGE = 1000

    // Trigger Variables
    private var lastAzimuthPoints: ArrayDeque<Float> = ArrayDeque(NUM_POINTS_TO_AVERAGE)
    private var azimuthLastMajor = 0.0f
    private var lastTrigger = 0L

//    private lateinit var mService: SensorMeasureService
//    private var mBound: Boolean = false

    // Defines callbacks for service binding, passed to bindService()
//    private val connection = object : ServiceConnection {
//        override fun onServiceConnected(className: ComponentName, service: IBinder) {
//            // We've bound to LocalService, cast the IBinder and get LocalService instance
//            val binder = service as SensorMeasureService.LocalBinder
//            mService = binder.getService()
//            mBound = true
//        }
//
//        override fun onServiceDisconnected(arg0: ComponentName) {
//            mBound = false
//        }
//    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        mActivity = activity as MainActivity
        mActivity.supportActionBar?.title = "Data Collection"

        batteryManager =
            this.requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        this.requireContext()
            .registerReceiver(mBatInfoReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // Reset kalman processor BEFORE location updates begin
        // kalmanProcessor.reset(8, 2)
        // kalmanProcessor.setLocationCallback(1000) { loc ->
        //    mActivity.locations.add(LocationWrapper(loc.getLatitude(), loc.getLongitude()))
        //    mProcessedLocation = loc
        // }

        // createLocationRequest()
        buildGoogleApiClient()
        mGoogleApiClient.connect()
        locationManager =
            this.requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        locationProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER)
        gnssStatusCallback = object : GnssStatus.Callback() {
            //All of these functions will only be called if the status of the hardware changes!
            //So, if gps receiver was on before the app starts, then these might not be called in our app
            //For that reason, you might see a logfile that has all "GPS OFF"...it could actually have been on the whole time!
            //You could try investigating the unimplemented methods for the above locationListener/location manager to see if a solution lies there
            override fun onStarted() {
                super.onStarted()
                csvData.add("--GPS STARTED--\n")
            }

            override fun onFirstFix(ttffMillis: Int) {
                super.onFirstFix(ttffMillis)
                mGPSFirstFix = true
                csvData.add("--GPS FIRST FIX LOCKED--\n")
                // Toast.makeText(this@FirstFragment.requireContext(), "GPS Fix Locked", Toast.LENGTH_SHORT).show()
                // timeToFirstFixMS = ttffMillis
                // firstFixLock.open() //GPS has a signal and can transmit location, release the navigate thread
            }

            override fun onStopped() {
                super.onStopped()
                mGPSFirstFix = false
                csvData.add("--GPS STOPPED--\n")
                // timeToFirstFixMS = -1
                // firstFixLock.close() //Since the GPS is now off, block the navigate thread from doing location things
            }
        }
        locationListener = LocationListener { location ->
            mCurrentLocation = location
            csvData.add("--GPS LOCATION CHANGED--\n")
        }

        // fusedLocationClient =
        //    LocationServices.getFusedLocationProviderClient(this.requireActivity())

        sensorManager =
            this.requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accelerometerRaw = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, accelerometerRaw, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)

        val now = Time(Time.getCurrentTimezone())
        now.setToNow()
        lastTrigger = now.toMillis(false)

        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            1 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (ContextCompat.checkSelfPermission(
                            this.requireContext(),
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        Toast.makeText(
                            this.requireContext(),
                            "Acquired permission",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this.requireContext(),
                        "Failed to acquire permission",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonGotomap.setOnClickListener {
            saveCSVData()
            val intent = Intent(mActivity, TrackDisplayActivity::class.java)
            // val args = Bundle()
            // val lat = mActivity.locations[0].latLng[0]
            // val lng = mActivity.locations[0].latLng[1]
            // Toast.makeText(this.requireContext(), "Got: lat=$lat and lng=$lng", Toast.LENGTH_SHORT).show()
            // args.putParcelableArrayList("LOCATIONS", mActivity.locations)
            intent.putParcelableArrayListExtra("LOCATIONS", mActivity.locations)
            // var i = 0
            // for ( locWrap in mActivity.locations ) {
            //     val lat = locWrap.latLng[0]
            //     val lng = locWrap.latLng[1]
            //     intent.putExtra("lat$i", lat)
            //     intent.putExtra("lng$i", lat)
            //     i++
            // }
            // intent.putExtra("size", i)
            startActivity(intent)
            // findNavController().navigate(R.id.action_FirstFragment_to_MapFragment)
        }

        binding.buttonSave.setOnClickListener { _ ->
            saveCSVData()
        }
        binding.buttonLeft.setOnClickListener { _ ->
            csvData.add("--LEFT--\n")
        }
        binding.buttonRight.setOnClickListener { _ ->
            csvData.add("--RIGHT--\n")
        }
        binding.buttonStop.setOnClickListener { _ ->
            csvData.add("--STOP--\n")
        }
        binding.buttonToggleGps.setOnClickListener { _ ->
            if (mGPSListening) {
                stopLocationUpdates()
            } else {
                if (mServicesConnected) {
                    startLocationUpdates()
                }
            }
        }
        binding.switchSmart.setOnClickListener {
            mDutyCyclingEnabled = binding.switchSmart.isChecked
        }

        if (ActivityCompat.checkSelfPermission(
                this.requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this.requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this.requireActivity(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            ) {
                AlertDialog.Builder(this.requireContext())
                    .setTitle("Location Required")
                    .setMessage("Location permission required to use this application.")
                    .setPositiveButton("OK", DialogInterface.OnClickListener { _, _ ->
                        {
                            ActivityCompat.requestPermissions(
                                this.requireActivity(),
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                1
                            )
                        }
                    })
                    .create()
                    .show()
            } else ActivityCompat.requestPermissions(
                this.requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )

            return
        }

        locationManager.registerGnssStatusCallback(
            gnssStatusCallback,
            Handler(Looper.getMainLooper())
        )

//        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
//            if (location == null) {
//                return@addOnSuccessListener;
//            }
//            Log.d("firstFragment", "SUCCEEDED IN ACQUIRING LOCATION SERVICES")
//            mCurrentLocation = location
//            updateUI()
//        }
//        fusedLocationClient.lastLocation.addOnFailureListener { it ->
//            it.printStackTrace()
//        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
//        this.requireActivity().unbindService(connection)
//        mBound = false
        stopLocationUpdates()
        saveCSVData()
        _binding = null
    }

    private fun saveCSVData() {
        val now = Time(Time.getCurrentTimezone())
        now.setToNow()

        val name: StringBuilder = StringBuilder()
        name.append(now.year.toString() + "") // Year)

        name.append("_")
        name.append(now.monthDay.toString() + "") // Day of the month (1-31)

        name.append("_")
        name.append((now.month + 1).toString() + "") // Month (1-12))

        name.append("_")
        name.append(now.format("%k-%M-%S")) // Current time

        name.append("_${mDutyCyclingEnabled.toString()}.txt")
        val csv = File(
            this.requireContext().getExternalFilesDir(null)!!,
            name.toString()
        )
        if (!csv.exists()) {
            csv.parentFile.mkdirs()
//            Toast.makeText(this.requireContext(), "Could not make file", Toast.LENGTH_SHORT).show()
//            return
        }
        val writer = FileWriter(csv)
        // Header line
        writer.write("lat,lon,alt,acc,speed,accelx,accely,accelz,gyrox,gyroy,gyroz,azimuth,pitch,roll,time,batpct,current\n")
        // Lines added
        for (line in csvData) {
            writer.write(line)
        }
        writer.flush()

        writer.close()

        val saveDir = this.requireContext().getExternalFilesDir(null)!!.absolutePath
        binding.tvSaving.text = saveDir
        Toast.makeText(this.requireContext(), "Saved to file $saveDir/$name", Toast.LENGTH_SHORT)
            .show()

        // val reader = FileReader(csv)
        // val firstLine = reader.readLines()[0]
        // Toast.makeText(this.requireContext(), "Proof line $firstLine", Toast.LENGTH_SHORT).show()
    }

    // private fun createLocationRequest() {
    //     mLocationRequest = LocationRequest()
    //     mLocationRequest.interval = primaryInterval;
    //     mLocationRequest.fastestInterval = fastestInterval;
    //     mLocationRequest.priority = Priority.PRIORITY_HIGH_ACCURACY;
    // }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this.requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this.requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this.requireActivity(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            ) {
                AlertDialog.Builder(this.requireContext())
                    .setTitle("Location Required")
                    .setMessage("Location permission required to use this application.")
                    .setPositiveButton("OK", DialogInterface.OnClickListener { _, _ ->
                        {
                            ActivityCompat.requestPermissions(
                                this.requireActivity(),
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                                1
                            )
                        }
                    })
                    .create()
                    .show()
            } else ActivityCompat.requestPermissions(
                this.requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )

            return
        }

        binding.tvUpdates.text = "On"
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            this
        )
        mGPSListening = true
    }

    private fun stopLocationUpdates() {
        if (mGPSListening) {
            binding.tvUpdates.text = "Off"
            locationManager.removeUpdates(this)
            mGPSListening = false
            mGPSFirstFix = false
        }
    }

    @Synchronized
    private fun buildGoogleApiClient() {
        mGoogleApiClient = GoogleApiClient.Builder(this.requireContext())
            .addConnectionCallbacks(this)
            .addOnConnectionFailedListener(this)
            .addApi(LocationServices.API)
            .build()
    }

    override fun onConnected(p0: Bundle?) {
        binding.tvApi.text = "Service Connected"
        mServicesConnected = true
        startLocationUpdates()
    }

    override fun onConnectionSuspended(p0: Int) {
        binding.tvApi.text = "Suspended"
        mServicesConnected = false
        Log.e("firstFragment", "CONNECTION SUSPENDED TO GOOGLE API CLIENT!")
    }

    override fun onConnectionFailed(p0: ConnectionResult) {
        binding.tvApi.text = "Failed"
        mServicesConnected = false
        Log.e("firstFragment", "FATAL CONNECTION FAILURE TO GOOGLE API CLIENT!")
    }

    override fun onLocationChanged(location: Location) {
        // if (!mGPSListening) return
        // kalmanProcessor.process(location)
        val now = Time(Time.getCurrentTimezone())
        now.setToNow()
        val curr = now.toMillis(false)
        if (mGPSFirstFix && curr - lastTrigger >= GPS_START_TIME)
            mActivity.locations.add(LocationWrapper(location))

        mCurrentLocation = location
        csvData.add("--GPS LOCATION CHANGED--\n")
        // addDataLine()
        updateUI()
    }

    private fun addDataLine() {
        // lat,lon,alt,acc,speed,accelx,accely,accelz,gyrox,gyroy,gyroz,azimuth,pitch,roll,time,batpct,current
        val sb = StringBuilder()

        // Location
        if (mCurrentLocation != null) {
            sb.append(mCurrentLocation!!.getLatitude().toString())
            sb.append(",")
            sb.append(mCurrentLocation!!.getLongitude().toString())
            sb.append(",")
            sb.append(mCurrentLocation!!.getAltitude().toString())
            sb.append(",")
            sb.append(mCurrentLocation!!.getAccuracy().toString())
            sb.append(",")
            sb.append(mCurrentLocation!!.getSpeed().toString())
            sb.append(",")
        } else {
            sb.append("0.0")
            sb.append(",")
            sb.append("0.0")
            sb.append(",")
            sb.append("0.0")
            sb.append(",")
            sb.append("0.0")
            sb.append(",")
            sb.append("0.0")
            sb.append(",")
        }

        // Accelerometer
        sb.append(mLinearAccel[0].toString())
        sb.append(",")
        sb.append(mLinearAccel[1].toString())
        sb.append(",")
        sb.append(mLinearAccel[2].toString())
        sb.append(",")

        // Gyroscope
        sb.append(mGyroRot[0].toString())
        sb.append(",")
        sb.append(mGyroRot[1].toString())
        sb.append(",")
        sb.append(mGyroRot[2].toString())
        sb.append(",")

        // Orientation
        sb.append(orientationAngles[0].toString())
        sb.append(",")
        sb.append(orientationAngles[1].toString())
        sb.append(",")
        sb.append(orientationAngles[2].toString())
        sb.append(",")

        val now = Time(Time.getCurrentTimezone())
        now.setToNow()
        sb.append(now.toMillis(false))
        sb.append(",")

        sb.append(mBatteryPct.toString())
        sb.append(",")
        sb.append(mBatteryMicroAmps.toString())

        sb.append("\n")
        csvData.add(sb.toString())
    }

    private fun updateUI() {
        // Location
        if (mCurrentLocation != null) {
            binding.tvLat.text = mCurrentLocation!!.getLatitude().toString()
            binding.tvLon.text = mCurrentLocation!!.getLongitude().toString()
            binding.tvAltitude.text = mCurrentLocation!!.getAltitude().toString()
            binding.tvAccuracy.text = mCurrentLocation!!.getAccuracy().toString()
            binding.tvSpeed.text = mCurrentLocation!!.getSpeed().toString()
        }
        // Accelerometer
        binding.tvAccelx.text = mLinearAccel[0].toString()
        binding.tvAccely.text = mLinearAccel[1].toString()
        binding.tvAccelz.text = mLinearAccel[2].toString()

        // Gyroscope
        binding.tvGyrox.text = mGyroRot[0].toString()
        binding.tvGyroy.text = mGyroRot[1].toString()
        binding.tvGyroz.text = mGyroRot[2].toString()

        binding.tvAzimuth.text = orientationAngles[0].toString() + "°"
        binding.tvPitch.text = orientationAngles[1].toString() + "°"
        binding.tvRoll.text = orientationAngles[2].toString() + "°"
    }

    private fun updateOrientation() {
        // Update rotation matrix, which is needed to update orientation angles.
        SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            mAccelRaw,
            mMagneto
        )

        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        orientationAngles = orientationAngles.map {
            radToDeg(it)
        }.toFloatArray()

        if (lastAzimuthPoints.size == NUM_POINTS_TO_AVERAGE) {
            // Size of deque should never be greater than 100 or 1000, whichever is chosen
            lastAzimuthPoints.removeFirst()
        }
        lastAzimuthPoints.addLast(orientationAngles[0])

        // orientationAngles[0] = orientationAngles[0] + 180f
        if (mDutyCyclingEnabled)
            dutyCycle()
    }

    private fun dutyCycle() {
        var now = Time(Time.getCurrentTimezone())
        now.setToNow()
        val curr = now.toMillis(false)
//        Toast.makeText(this.requireContext(),"${mGPSListening} ${mGPSFirstFix} ${mGoogleApiClient.isConnected} ${curr - lastTrigger}",Toast.LENGTH_SHORT).show()
        if (mGPSListening
            && mGPSFirstFix
            && mGoogleApiClient.isConnected
            && abs(abs(orientationAngles[0]) - abs(azimuthLastMajor)) < AZIMUTH_TRIGGER_DIFFERENCE
            && curr - lastTrigger >= GPS_CYCLE_OFF_TIME
        ) {
            lastTrigger = curr
            azimuthLastMajor = lastAzimuthPoints.average().toFloat()
            binding.tvUpdates.text = "Cycled Off"
            stopLocationUpdates()
        }else if (abs(abs(orientationAngles[0]) - abs(azimuthLastMajor)) >= AZIMUTH_TRIGGER_DIFFERENCE) {
            if (curr - lastTrigger > GPS_CYCLE_SAVE_THRESHOLD_TIME
                && !mGPSListening
                && mGoogleApiClient.isConnected
            ) {
                lastTrigger = curr
                azimuthLastMajor = lastAzimuthPoints.average().toFloat()
                binding.tvUpdates.text = "Cycled On"
                startLocationUpdates()
            }
        }
    } //end function

    private fun norm(x: Float, y: Float, z: Float): Float {
        return sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    }

    private fun clamp(input: Float, min: Float, max: Float): Float {
        return if (input < min)
            min
        else if (input > max)
            max
        else
            input
    }

    private fun firFilter(
        filter_x: Float,
        filter_y: Float,
        filter_z: Float,
        actual_x: Float,
        actual_y: Float,
        actual_z: Float,
        last_x: Float,
        last_y: Float,
        last_z: Float
    ): Triple<Float, Float, Float> {
        /* From https://stackoverflow.com/a/8323572/7486918 */
        val updateFreq = 30f // match this to your update speed (which I am not sure of)
        val cutOffFreq = 0.1f
        val RC = 1.0f / cutOffFreq
        val dt = 1.0f / updateFreq
        val filterConstant = RC / (dt + RC)
        val kMinStep = 0.00001f
        val kNoiseAttenuation = 3.0f

        val d: Float = clamp(
            abs(
                norm(
                    filter_x,
                    filter_y,
                    filter_z
                ) - norm(actual_x, actual_y, actual_z)
            ) / kMinStep - 1.0f, 0.0f, 1.0f
        )
        alpha =
            d * filterConstant / kNoiseAttenuation + (1.0f - d) * filterConstant

        val x = (alpha * (filter_x + actual_x - last_x));
        val y = (alpha * (filter_y + actual_y - last_y));
        val z = (alpha * (filter_z + actual_z - last_z));

        return Triple(x, y, z)
    }

    private fun radToDeg(radians: Float): Float {
        return ((radians * 180.0f).toDouble() / Math.PI).toFloat()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
            var updated = false
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val (laX, laY, laZ) = firFilter(
                        mLinearAccel[0], mLinearAccel[1], mLinearAccel[2],
                        event.values[0], event.values[1], event.values[2],
                        mLinearAccelLast[0], mLinearAccelLast[1], mLinearAccelLast[2]
                    )

                    mLinearAccelLast[0] = event.values[0]
                    mLinearAccelLast[1] = event.values[1]
                    mLinearAccelLast[2] = event.values[2]

                    mLinearAccel[0] = laX
                    mLinearAccel[1] = laY
                    mLinearAccel[2] = laZ

                    updated = true
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val (gyX, gyY, gyZ) = firFilter(
                        mGyroRot[0], mGyroRot[1], mGyroRot[2],
                        event.values[0], event.values[1], event.values[2],
                        mGyroLast[0], mGyroLast[1], mGyroLast[2]
                    )

                    mGyroLast[0] = event.values[0]
                    mGyroLast[1] = event.values[1]
                    mGyroLast[2] = event.values[2]

                    mGyroRot[0] = gyX
                    mGyroRot[1] = gyY
                    mGyroRot[2] = gyZ

                    updated = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    // val (mgX, mgY, mgZ) = firFilter(
                    //     mMagneto[0], mMagneto[1], mMagneto[2],
                    //     event.values[0], event.values[1], event.values[2],
                    //     mMagnetoLast[0], mMagnetoLast[1], mMagnetoLast[2]
                    // )

                    mMagnetoLast[0] = mMagneto[0]
                    mMagnetoLast[1] = mMagneto[1]
                    mMagnetoLast[2] = mMagneto[2]

                    mMagneto[0] = event.values[0]
                    mMagneto[1] = event.values[1]
                    mMagneto[2] = event.values[2]

                    updated = true
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // val (acX, acY, acZ) = firFilter(
                    //     mAccelRaw[0], mAccelRaw[1], mAccelRaw[2],
                    //     event.values[0], event.values[1], event.values[2],
                    //     mAccelRawLast[0], mAccelRawLast[1], mAccelRawLast[2]
                    // )

                    mAccelRawLast[0] = event.values[0]
                    mAccelRawLast[1] = event.values[1]
                    mAccelRawLast[2] = event.values[2]

                    mAccelRaw[0] = event.values[0]
                    mAccelRaw[1] = event.values[1]
                    mAccelRaw[2] = event.values[2]

                    updated = true
                }
            }
            if (updated) {
                updateOrientation()
                addDataLine()
                updateUI()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        return // ?
    }

}
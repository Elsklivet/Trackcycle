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
import com.elsklivet.trackcycle.databinding.FragmentDatacollectionBinding
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationServices
import java.io.File
import java.io.FileWriter
import kotlin.math.abs
import kotlin.math.sqrt


/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class DataCollectionFragment : Fragment(),
    GoogleApiClient.ConnectionCallbacks,
    GoogleApiClient.OnConnectionFailedListener,
    LocationListener,
    SensorEventListener {

    private var _binding: FragmentDatacollectionBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    // Activity
    private lateinit var mActivity: MainActivity

    // Intervals
    private var primaryInterval = 1000L;
    private var fastestInterval = 1000L;

    // Location Services
    private lateinit var locationManager: LocationManager

    private var locationProvider: LocationProvider? = null
    private lateinit var gnssStatusCallback: GnssStatus.Callback
    private lateinit var locationListener: LocationListener

    // Google Sign-In Client
    // private lateinit var mSignInClient: GoogleSignInClient
    // Google API Client
    private lateinit var mGoogleApiClient: GoogleApiClient

    // Current Location
    private var mCurrentLocation: Location? = null

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
    private var mBatteryCapMAH: Int = 0
    private var mBatteryEnergyNWH: Long = 0

    // Battery Receiver
    private var mBatInfoReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context, intent: Intent) {
            mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            mBatteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            mBatteryPct = mBatteryLevel * 100 / mBatteryScale.toFloat()
            mBatteryMicroAmps =
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            mBatteryCapMAH =
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            mBatteryEnergyNWH =
                batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        }
    }

    // Duty Cycling Flag
    private var mDutyCyclingEnabled: Boolean = true

    // Constants
    private val GPS_START_TIME = 14L * 1000L
    private val GPS_CYCLE_SAVE_THRESHOLD_TIME = 10L * 1000L
    private val GPS_CYCLE_OFF_TIME = GPS_START_TIME + GPS_CYCLE_SAVE_THRESHOLD_TIME
    private val AZIMUTH_TRIGGER_DIFFERENCE = 60
    private val NUM_POINTS_TO_AVERAGE = 1000

    // Trigger Variables
    private var lastAzimuthPoints: ArrayDeque<Float> = ArrayDeque(NUM_POINTS_TO_AVERAGE)
    private var azimuthLastMajor = 0f
    private var lastTrigger = 0L
    private var dutyCycleInitialized = false


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

        buildGoogleApiClient()
        mGoogleApiClient.connect()
        locationManager =
            this.requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        locationProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER)
        gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onStarted() {
                super.onStarted()
                csvData.add("--GPS STARTED--\n")
            }

            override fun onFirstFix(ttffMillis: Int) {
                super.onFirstFix(ttffMillis)
                mGPSFirstFix = true
                csvData.add("--GPS FIRST FIX LOCKED--\n")
            }

            override fun onStopped() {
                super.onStopped()
                mGPSFirstFix = false
                csvData.add("--GPS STOPPED--\n")
            }
        }
        locationListener = LocationListener { location ->
            mCurrentLocation = location
            csvData.add("--GPS LOCATION CHANGED--\n")
        }

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

        _binding = FragmentDatacollectionBinding.inflate(inflater, container, false)
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
            intent.putParcelableArrayListExtra("LOCATIONS", mActivity.locations)
            startActivity(intent)
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
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
            csv.parentFile?.mkdirs()
        }
        val writer = FileWriter(csv)
        // Header line
        writer.write("lat,lon,alt,acc,speed,accelx,accely,accelz,gyrox,gyroy,gyroz,azimuth,pitch,roll,time,batpct,current,capmah,engnwh\n")
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
    }

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
        // lat,lon,alt,acc,speed,accelx,accely,accelz,gyrox,gyroy,gyroz,azimuth,pitch,roll,time,batpct,current,capmah,engnwh
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
        sb.append(",")
        sb.append(mBatteryCapMAH.toString())
        sb.append(",")
        sb.append(mBatteryEnergyNWH.toString())

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
        val checkAngle = lastAzimuthPoints.average().toFloat()

        // Really hacky fix for the fact that the GPS never turns off at the beginning
        if (!dutyCycleInitialized
            && azimuthLastMajor == 0f
            && curr - lastTrigger >= GPS_CYCLE_OFF_TIME
        ) {
            azimuthLastMajor = checkAngle
            dutyCycleInitialized = true
        }

        if (mGPSListening
            && mGPSFirstFix
            && mGoogleApiClient.isConnected
            && abs(abs(checkAngle) - abs(azimuthLastMajor)) < AZIMUTH_TRIGGER_DIFFERENCE
            && curr - lastTrigger >= GPS_CYCLE_OFF_TIME
        ) {
            lastTrigger = curr
            azimuthLastMajor = lastAzimuthPoints.average().toFloat()
            binding.tvUpdates.text = "Cycled Off"
            stopLocationUpdates()
        } else if (abs(abs(checkAngle) - abs(azimuthLastMajor)) >= AZIMUTH_TRIGGER_DIFFERENCE) {
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

    private fun radToDeg(radians: Float): Float {
        return ((radians * 180.0f).toDouble() / Math.PI).toFloat()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null) {
            var updated = false
            when (event.sensor.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    mLinearAccelLast[0] = mLinearAccel[0]
                    mLinearAccelLast[1] = mLinearAccel[1]
                    mLinearAccelLast[2] = mLinearAccel[2]

                    mLinearAccel[0] = event.values[0]
                    mLinearAccel[1] = event.values[1]
                    mLinearAccel[2] = event.values[2]

                    updated = true
                }
                Sensor.TYPE_GYROSCOPE -> {

                    mGyroLast[0] = mGyroRot[0]
                    mGyroLast[1] = mGyroRot[1]
                    mGyroLast[2] = mGyroRot[2]

                    mGyroRot[0] = event.values[0]
                    mGyroRot[1] = event.values[1]
                    mGyroRot[2] = event.values[2]

                    updated = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {

                    mMagnetoLast[0] = mMagneto[0]
                    mMagnetoLast[1] = mMagneto[1]
                    mMagnetoLast[2] = mMagneto[2]

                    mMagneto[0] = event.values[0]
                    mMagneto[1] = event.values[1]
                    mMagneto[2] = event.values[2]

                    updated = true
                }
                Sensor.TYPE_ACCELEROMETER -> {


                    mAccelRawLast[0] = mAccelRaw[0]
                    mAccelRawLast[1] = mAccelRaw[1]
                    mAccelRawLast[2] = mAccelRaw[2]

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
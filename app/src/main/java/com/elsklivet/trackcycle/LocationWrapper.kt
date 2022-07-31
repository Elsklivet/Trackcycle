package com.elsklivet.trackcycle

// import com.google.android.gms.maps.model.LatLng
import android.location.Location
import android.os.Parcel
import android.os.Parcelable

// Make this parcelable or send it as a bundle...
class LocationWrapper() : Parcelable {
    var latLng = DoubleArray(2)
        get() = field

    constructor(parcel: Parcel) : this() {
        parcel.readDoubleArray(latLng)
    }

    constructor (loc: Location) : this() {
        latLng = doubleArrayOf(loc.latitude, loc.longitude)
    }

    constructor (lat: Double, lon: Double) : this() {
        latLng = doubleArrayOf(lat, lon)
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeDoubleArray(latLng)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<LocationWrapper> {
        override fun createFromParcel(parcel: Parcel): LocationWrapper {
            return LocationWrapper(parcel)
        }

        override fun newArray(size: Int): Array<LocationWrapper?> {
            return arrayOfNulls(size)
        }
    }
}
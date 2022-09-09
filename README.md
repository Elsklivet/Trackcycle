# Trackcycle

Trackcycle is a mobile application designed to track bicycle trips while minimizing device power usage, without accepting major accuracy sacrifices. The application uses duty-cycling triggered by using a combination of on-device inertial measurement unit (IMU) and magnetometer sensors to detect when bicycle riders have veered off of a straight, estimable path. It is written in the Kotlin programming language, targetting the Android operating system at API level 31. In addition to the core goal of maintaining minimal power usage/maximal accuracy, goals of the project at this time include to improve the duty-cycling mechanism's accuracy in detecting turns so that the GPS remains off more often than it currently does, enhance data collection used to evaluate power saving and accuracy, and to improve path estimation during off time.  

## Basis

Trackcycle's theoretical basis stands on the idea that global positioning systems (GPS) are oftentimes unreasonably power-hungry [[1]](#references), but also among the most accurate ways of tracking location. The basic idea of duty-cycling the GPS lies in the idea that, if a rider is on the same path for some period of time, or on an easily estimable trajectory, then the GPS does not need to be on and consuming power. Imagine that a rider rides for 30 minutes on a straight, country trail or road. This route can be estimated with just two collected GPS points: a start and an end. Using 30 minutes' worth of GPS power draw would be extremely wasteful.

Previous work showed that there is promise to the idea that duty-cycling GPS by using IMU sensors could save power [[2]](#references)[[3]](#references). Accelerometer and gyroscope data appears to be an effective trigger for short-length bicycle or vehicle turns—that is, turns which happen over a short distance and short period of time. However, consider a hypothetical situation in which a bicycle rider turns on a slight angle over the period of more than a minute or two, such that IMU measurements would change too incrementally to absolutely associate them with a deviation from previous trajectory. So, instead, it was considered that detecting a bearing in relation to compass north could be a more absolute determinant of trajectory changes. Thankfully, the Android SDK provides access to on-device magnetometers if they are available, which, when combined with IMU measurements, can be used to determine azimuth about compass north as part of the device rotation matrix. Trackcycle uses this azimuth as the basis for its GPS duty-cycling.

## Implementation

The application is written in Kotlin, targetting API level 31 of the Android OS, and requires on-device GPS, accelerometer, gyroscope, and magnetometer be present, as well as a signed-in Google Play account to use Google Play services for displaying the map of the route tracked. 

The duty-cycling is triggered by a change over threshold in average azimuth over a given number of points (note that sometimes approximately one hundred azimuth measurements can be taken in just one second) as well as a change over threshold in time since last duty-cycle, to ensure duty-cycling does not occur too often that it would be impossible to save power. 

Attempts have been made to filter the (extremely noisy) data collected by the IMU in order to improve performance, namely with FIR filters and Extended Kalman Filters (which were particularly cited as useful in this use case [[4]](#references)), but these have been unsuccessful, likely due to faulty implementation. This data is then fed into scripts located at [this repository](https://github.com/Elsklivet/Trackcycle-Data) to evaluate power and accuracy tradeoff, as well as to do sensitivity analysis and parameter analysis.

On a button press or when the map is viewed after a trip, the app logs data collected by the sensors used in duty-cycling as well as from sensors that collect battery information (for power measurements) and location information (to compare against estimations). 

## Contributing

For detailed contributing information, see [CONTRIBUTING.md](./CONTRIBUTING.md).

## Copyright Notices

Some of the code included in this project draws from several sources, including [Google Developer documentation](https://developer.android.com/docs) which are licensed under the [Apache License v2.0](./APACHE-LICENSE.txt), and other academic projects (with express permission). We've done our best to attribute appropriate sources for code included in the project. If you feel that your work has been incorrectly attributed per the appropriate license, please contact @Elsklivet at <gmh33@pitt.edu> or <gavinmajetich@gmail.com>. I will do my best to remedy any attribution or copyright problems immediately.

Any original code in this project is licensed under the [GNU GPL v3](./GPL-LICENSE.txt).

For contributors, it is **extremely important** that code you find is properly attributed and that copyrights are not infringed. Any contributions including code that is improperly attributed, in violation of the original work's copyright, or uses a copyright that is not permissive enough will be **rejected**.

## References

[1] Tawalbeh, M., Eardley, A., & Tawalbeh, L. (2016). “Studying the Energy Consumption in Mobile Devices.” *Procedia Computer Science*, 94, 183–189. https://doi.org/10.1016/j.procs.2016.08.028.

[2] Bhattacharya, S., Blunck, H., Kjargaard, M. B., & Nurmi, P. (2015). “Robust and Energy-Efficient Trajectory Tracking for Mobile Devices.” *IEEE Transactions on Mobile Computing*, 14(2), 430–443. https://doi.org/10.1109/tmc.2014.2318712.

[3] Ofstad, A., Nicholas, E., Szcodronski, R., & Choudhury, R. R. (2008). “AAMPL: Accelerometer Augmented Mobile Phone Localization.” *Proceedings of the First ACM International Workshop on Mobile Entity Localization and Tracking in GPS-Less Environments - MELT ’08*. https://doi.org/10.1145/1410012.1410016.

[4] Chang, H. W., Georgy, J., & El-Sheimy, N. (2014). “Cycling dead reckoning for enhanced portable device navigation on multi-gear bicycles.” *GPS Solutions*, 19(4), 611–621. https://doi.org/10.1007/s10291-014-0417-1.
package org.fitchfamily.android.dejavu

import android.location.Location
import android.net.wifi.ScanResult
import android.os.Bundle
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

private val DEBUG = BuildConfig.DEBUG
private const val TAG = "DejaVu Util"

// DEG_TO_METER is only approximate, but an error of 1% is acceptable
//  for latitude it depends on latitude, from ~110500 (equator) ~111700 (poles)
//  for longitude at equator it's ~111300
const val DEG_TO_METER = 111225.0
const val METER_TO_DEG = 1.0 / DEG_TO_METER
const val MIN_COS = 0.01 // for things that are dividing by the cosine

private const val NULL_ISLAND_DISTANCE = 1000f
private const val NULL_ISLAND_DISTANCE_DEG = NULL_ISLAND_DISTANCE * METER_TO_DEG

// Define range of received signal strength to be used for all emitter types.
// Basically use the same range of values for LTE and WiFi as GSM defaults to.
const val MAXIMUM_ASU = 31
const val MINIMUM_ASU = 1

// KPH -> Meters/millisec (KPH * 1000) / (60*60*1000) -> KPH/3600
//        const val EXPECTED_SPEED = 120.0f / 3600 // 120KPH (74 MPH)
const val LOCATION_PROVIDER = "DejaVu"
private const val MINIMUM_BELIEVABLE_ACCURACY = 15.0F

// much faster than location.distanceTo(otherLocation)
// and less than 0.1% difference the small (< 1°) distances we're interested in
fun approximateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val distLat = (lat1 - lat2)
    val distLon = (lon1 - lon2) * cos(Math.toRadians(lat1))
    return sqrt(distLat * distLat + distLon * distLon) * DEG_TO_METER
}

/**
 * Check if location too close to null island to be real
 *
 * @param loc The location to be checked
 * @return boolean True if away from lat,lon of 0,0
 */
fun notNullIsland(loc: Location): Boolean = notNullIsland(loc.latitude, loc.longitude)
// simplified check that should avoid distance calculation in almost every case where this return true
fun notNullIsland(lat: Double, lon: Double): Boolean {
    return abs(lat) > NULL_ISLAND_DISTANCE_DEG
            || abs(lon) > NULL_ISLAND_DISTANCE_DEG
            || approximateDistance(lat, lon, 0.0, 0.0) > NULL_ISLAND_DISTANCE
}

// wifiManager.is6GHzBandSupported might be called to check whether it can be WLAN6
// but wifiManager.is5GHzBandSupported incorrectly returns no on some devices, so can we trust
// it to be correct for 6 GHz?
// anyway, there might be a better way of determining WiFi type
fun ScanResult.getWifiType(): EmitterType =
    when {
        frequency < 3000 -> EmitterType.WLAN2 // 2401 - 2495 MHz
        // 5945 can be WLAN5 and WLAN6, simply don't bother and assume WLAN5 for now
        frequency <= 5945 -> EmitterType.WLAN5 // 5030 - 5990 MHz, but at 5945 WLAN6 starts
        frequency > 6000 -> EmitterType.WLAN6 // 5945 - 7125
        frequency % 10 == 5 -> EmitterType.WLAN6 // in the overlapping range, WLAN6 frequencies end with 5
        else -> EmitterType.WLAN5
    }

/**
 *
 * The collector service attempts to detect and not report moved/moving emitters.
 * But it (and thus our database) can't be perfect. This routine looks at all the
 * emitters and returns the largest subset (group) that are within a reasonable
 * distance of one another.
 *
 * The hope is that a single moved/moving emitters that is seen now but whose
 * location was detected miles away can be excluded from the set of APs
 * we use to determine where the phone is at this moment.
 *
 * We do this by creating collections of emitters where all the emitters in a group
 * are within a plausible distance of one another. A single emitters may end up
 * in multiple groups. When done, we return the largest group.
 *
 * If we are at the extreme limit of possible coverage (maximumRange)
 * from two emitters then those emitters could be a distance of 2*maximumRange apart.
 * So we will group the emitters based on that large distance.
 *
 * @param locations A collection of the coverages for the current observation set
 * @return The largest set of coverages found within the raw observations. That is
 * the most believable set of coverage areas.
 */
fun culledEmitters(locations: Collection<RfLocation>): Set<RfLocation>? {
    val groups = divideInGroups(locations)
    groups.maxByOrNull { it.size }?.let { result ->
        // if we only have one location, use it as long as it's not an invalid emitter
        if (locations.size == 1 && result.single().type != EmitterType.INVALID) {
            if (DEBUG) Log.d(TAG, "culledEmitters() - got only one location, use it")
            return result
        }
        // Determine minimum count for a valid group of emitters.
        // The RfEmitter class will have put the min count into the location
        // it provided.
        result.forEach {
            if (result.size >= it.type.getRfCharacteristics().minCount)
                return result
        }
        if (DEBUG) Log.d(TAG, "culledEmitters() - only got ${result.size}, but " +
                "${result.minOfOrNull { it.type.getRfCharacteristics().minCount }} are required")
    }
    // fallback: try second group. this triggers very rarely todo: check logs, so far the 2nd group never helped (2 cases)
    if (groups.size > 1) {
        groups.sortedBy { it.size }[1].let { result ->
            if (DEBUG) Log.d(TAG, "culledEmitters() - trying second group")
            if (result.any { it.type.getRfCharacteristics().minCount < result.size })
                return result
        }
    }
    return null
}

/**
 * Build a list of sets (or groups) each outer set member is a set of coverage of
 * reasonably near RF emitters. Basically we are grouping the raw observations
 * into clumps based on how believably close together they are. An outlying emitter
 * will likely be put into its own group. Our caller will take the largest set as
 * the most believable group of observations to use to compute a position.
 *
 * @param locations A set of RF emitter coverage records
 * @return A list of coverage sets.
 */
private fun divideInGroups(locations: Collection<RfLocation>): List<MutableSet<RfLocation>> {
    // Create bins
    val bins = locations.map { hashSetOf(it) }
    for (location in locations) {
        for (locationGroup in bins) {
            if (locationCompatibleWithGroup(location, locationGroup)) {
                locationGroup.add(location)
            }
        }
    }
    return bins
}

/**
 * Check to see if the coverage area (location) of an RF emitter is close
 * enough to others in a group that we can believably add it to the group.
 * @param location The coverage area of the candidate emitter
 * @param locGroup The coverage areas of the emitters already in the group
 * @return True if location is close to others in group
 */
private fun locationCompatibleWithGroup(location: RfLocation, locGroup: Set<RfLocation>): Boolean {
    // If the location is within range of all current members of the
    // group, then we are compatible.
    for (other in locGroup) {
        // allow somewhat larger distance than sum of accuracies, looks like results are usually a bit better
        if (approximateDistance(location.lat, location.lon, other.lat, other.lon) > (location.accuracyEstimate + other.accuracyEstimate) * 1.25) {
            return false
        }
    }
    return true
}

/**
 * Shorter version of the original WeightedAverage, with adjusted weight to consider emitters
 * we don't know much about.
 * This ignores multiplying longitude accuracy by cosLat when converting to degrees, and
 * later dividing by cosLat when converting back to meters. It doesn't cancel out completely
 * because the used latitudes generally are slightly different, but differences are negligible
 * for our use.
 */
// main difference to the old WeightedAverage: accuracy is also influenced by how far
// apart the emitters are (sounds more relevant than it is, due to only "compatible" locations
// being used anyway)
fun Collection<RfLocation>.weightedAverage(): Location {
    val latitudes = DoubleArray(size)
    val longitudes = DoubleArray(size)
    val accuracies = DoubleArray(size)
    val weights = DoubleArray(size)
    forEachIndexed { i, it ->
        latitudes[i] = it.lat
        longitudes[i] = it.lon
        val minRange = it.type.getRfCharacteristics().minimumRange
        // significantly reduce asu if we don't really trust the location, but don't discard it
        val asu = if (it.suspicious) (it.asu / 4).coerceAtLeast(MINIMUM_ASU) else it.asu
        weights[i] = asu / it.accuracyEstimate

        // The actual accuracy we want to use for this location is an adjusted accuracyEstimate.
        // If asu is good, we're likely close to the emitter, so we can decrease accuracy value.
        // asuAdjustedAccuracy varies between minRange and accuracyEstimate
        val asuAdjustedAccuracy = minRange + (1.0 - ((asu - MINIMUM_ASU) * 1.0 / MAXIMUM_ASU)) * (it.accuracyEstimate - minRange)

        // <Comment on the factor 0.5 from original WeightedAverage.java>
        // Our input has an accuracy based on the detection of the edge of the coverage area.
        // So assume that is a high (two sigma) probability and, worse, assume we can turn that
        // into normal distribution error statistic. We will assume our standard deviation (one
        // sigma) is half of our accuracy.
        accuracies[i] = asuAdjustedAccuracy * METER_TO_DEG * 0.5
    }
    // set weighted means
    val latMean = weightedMean(latitudes, weights)
    val lonMean = weightedMean(longitudes, weights)
    // and variances, to use for accuracy
    val hasWifi = any { it.type in shortRangeEmitterTypes }
    val latVariance = weightedVariance(latMean, latitudes, accuracies, weights, hasWifi)
    val lonVariance = weightedVariance(lonMean, longitudes, accuracies, weights, hasWifi)
    val acc = (sqrt(latVariance + lonVariance) * DEG_TO_METER)
    // seen weirdly bad results if only 1 emitter is available, and we only have seen it in
    // very few locations -> need to catch this
    // similar if all WiFis are suspicious... don't trust it
    val allWifisSuspicious = hasWifi && none { !it.suspicious && it.type in shortRangeEmitterTypes }
    val reportAcc = acc * if (allWifisSuspicious || (size == 1 && first().radius < single().type.getRfCharacteristics().minimumRange))
        2.0 else 1.0
    return location(latMean, lonMean, reportAcc.toFloat())
}

fun Collection<RfLocation>.location(lat: Double, lon: Double, acc: Float): Location =
    Location(LOCATION_PROVIDER).apply {
        extras = Bundle().apply { putInt("AVERAGED_OF", size) }

        // set newest times
        time = maxOf { it.time }
        elapsedRealtimeNanos = maxOf { it.elapsedRealtimeNanos }

        latitude = lat
        longitude = lon
        accuracy = acc.coerceAtLeast(MINIMUM_BELIEVABLE_ACCURACY)
    }

/**
 * @returns the weighted mean of the given positions, accuracies and weights
 */
private fun weightedMean(positions: DoubleArray, weights: DoubleArray): Double {
    var weightedSum = 0.0
    positions.forEachIndexed { i, position ->
        weightedSum += position * weights[i]
    }
    return weightedSum / weights.sum()
}

/**
 * @returns the weighted variance of the given positions, accuracies and weights.
 * Variance and not stdDev because we need to square it anyway
 *
 * Actually this is not really correct, but it's good enough...
 * What we want from accuracy:
 *  more (very) similar locations should improve accuracy
 *  positions far apart should give worse accuracy, even if the single accuracies are similar
 */
private fun weightedVariance(weightedMeanPosition: Double, positions: DoubleArray, accuracies: DoubleArray, weights: DoubleArray, betterAccuracy: Boolean): Double {
    // we have a situation like
    // https://stats.stackexchange.com/questions/454120/how-can-i-calculate-uncertainty-of-the-mean-of-a-set-of-samples-with-different-u#comment844099_454266
    // but we already have weights... so come up with something that gives reasonable results
    var weightedVarianceSum = 0.0
    positions.forEachIndexed { i, position ->
        weightedVarianceSum += if (betterAccuracy) {
            // usually 5-20% better accuracy, but often not nice if we don't have any wifis
            val dev = max(accuracies[i], abs(position - weightedMeanPosition))
            weights[i] * weights[i] * dev * dev
        } else
            weights[i] * weights[i] * (accuracies[i] * accuracies[i] + (position - weightedMeanPosition) * (position - weightedMeanPosition))
    }

    // this is not really variance, but still similar enough to claim it is
    // dividing by size should be fine...
    return weightedVarianceSum / weights.sumOf { it * it }
}

// weighted average with removing outliers (more than 2 accuracies away from median center)
// no, then an outlier may still destroy the center
// need median center!
// and use only short range emitters if any are available
fun Collection<RfLocation>.medianCull(): Collection<RfLocation>? {
    if (isEmpty()) return null
    // use trustworthy wifi results for median location, but only if at least 3 emitters
    // if we have less than 3 results, also use suspicious results
    // if we still have less than 3 results, use all
    // 3 results because with less there is a too high chance of bad results
    val emittersForMedian = filter { it.type in shortRangeEmitterTypes && !it.suspicious }
        .let { goodList ->
            if (goodList.size >= 3) goodList
            else this.filter { it.type in shortRangeEmitterTypes }
                .let { okList ->
                    if (okList.size >= 3) okList
                    else this
                }
        }
    // take median of lat and lon separately
    // this can lead to unexpected and bad results, which should be caught in newThing
    val latMedian = emittersForMedian.map { it.lat }.median()
    val lonMedian = emittersForMedian.map { it.lon }.median()
    // use locations that are close enough to the median location (2 * their accuracy)
    // todo: maybe reduce factor 2 to 1.5 or sth like this? but we really just want to remove outliers
    val closeToMedian = filter { approximateDistance(latMedian, lonMedian, it.lat, it.lon) < 2.0 * it.accuracyEstimate }
    if (DEBUG) Log.d(TAG, "medianCull() - using ${closeToMedian.size} of initially $size locations")
    return closeToMedian.ifEmpty { culledEmitters(this) } // fallback to original culledEmitters
}

private fun List<Double>.median() = sorted().let {
    if (size % 2 == 1) it[size / 2]
    else (it[size / 2] + it[(size - 1) / 2]) / 2
}

fun Collection<RfLocation>.medianCullSafe(): Location? {
    val medianCull = medianCull() ?: return null // returns null if list is empty
    /* Need to decide whether to really use medianCull, because in some cases it produces
     * bad results. To detect such cases we use a more exhaustive check if :
     * a. Any locations have been removed, and the resulting locations does not fit with noCullLoc,
     *   i.e. they are further apart than the smaller accuracy
     * b. Too many locations have been removed. This can happen if medianCullLoc is at some
     *   bad location, e.g. between 2 WiFi groups, or it's messed up because lat and lon
     *   are treated independently in medianCull()
     * c. All WiFi emitters have been removed. This should not happen, but still does in some cases
     *   like when we have a single WiFi that is far away from mobile emitters
     * If any check returns true, we also create normalCullLoc and use whichever of the three
     * locations is closest to their center.
     */
    if (medianCull.size == size) return this.weightedAverage() // nothing removed, all should be fine
    val medianCullLoc = medianCull.weightedAverage()
    val noCullLoc = weightedAverage()
    val d = approximateDistance(medianCullLoc.latitude, medianCullLoc.longitude, noCullLoc.latitude, noCullLoc.longitude)
    if (d > medianCullLoc.accuracy
        || d > noCullLoc.accuracy
        || medianCull.size <= size * 0.8
        || (medianCull.none { it.type in shortRangeEmitterTypes } && this.any { it.type in shortRangeEmitterTypes })
    ) {
        // we have a potentially bad location -> check normal cull and no cull and compare
        val normalCullLoc = culledEmitters(this)?.weightedAverage()
        val locs = listOfNotNull(medianCullLoc, noCullLoc, normalCullLoc)
        val meanLat = locs.sumOf { it.latitude } / locs.size
        val meanLon = locs.sumOf { it.longitude } / locs.size
        val l = locs.minByOrNull {
            approximateDistance(meanLat, meanLon, it.latitude, it.longitude)
        }
        // this very often results in noCull, which may be much less accurate than the other 2
        // so try using medianCull location instead if it seems reasonably accurate
        if (l == noCullLoc && noCullLoc.accuracy > 2.0 * medianCullLoc.accuracy
            && approximateDistance(noCullLoc.latitude, noCullLoc.longitude, medianCullLoc.latitude, medianCullLoc.longitude) < noCullLoc.accuracy
        ) {
            if (DEBUG) Log.d(TAG, "medianCullSafe: using medianCull because chosen noCull is close but much less accurate")
            return medianCullLoc
        }
        if (DEBUG) {
            if (l == medianCullLoc)
                Log.d(TAG, "medianCullSafe: checked medianCull, still using")
            else
                Log.d(TAG, "medianCullSafe: not using medianCull")
        }
        return l
    }
    return medianCullLoc
}